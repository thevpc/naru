package net.thevpc.naru.ext.tools.tasks;

import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.spawn.NaruSpawnContract;
import net.thevpc.naru.api.spawn.NaruSpawnInherit;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.spawn.NaruSpawnResolution;
import net.thevpc.naru.api.spawn.NaruSpawnStrategy;
import net.thevpc.naru.api.spawn.NaruSpawnTargets;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NRef;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code /start <routine...> [flags]} — start one or more routines (or an agent
 * {@code .md}) as a single consecutive new task, with the WP3 spawn flags:
 *
 * <pre>
 * /start --fork                       fork the parent conversation (also inherits tags by default)
 * /start --window=6turns              seed the last 6 turns of the parent conversation
 * /start --summary                    seed a summary of the parent conversation (needs a compactor)
 * /start --inherit=tags,env           snapshot-inherit granted tags and/or env
 * /start --add-tags=exec,write        grant tags on top of the resolution
 * /start --revoke-tags=write,exec     revoke tags from the resolution (add wins over revoke)
 * /start --exclude-tools=run_shell    exclude tools
 * /start --add-skills=code-review     add skills (needs the skills extension installed)
 * /start --policy=review-safe review  apply a named policy defined with /spawn-policy
 * /start --explain review             print the resolved policy + provenance, spawn nothing
 * </pre>
 *
 * The target is a routine (name or path) whose task body becomes the child statements, or
 * an agent {@code .md} file whose front-matter contract ({@code requires} / {@code skills})
 * is validated against the resolved sets.
 */
public class NaruStartDirective extends NaruDirectiveBase {

    public NaruStartDirective() {
        super("start", "task", "start new task");
        register(new AbstractSubCommand(new SubCommandHelp("<routine>...", "start one or more routines as a single consecutive new task\n<routine> can be routine name or routine path; an agent .md name is also accepted\n\nflags:\n  --fork | --summary | --window=<n>turns\n  --inherit=tags[,env]\n  --add-tags=<tags> --revoke-tags=<tags>\n  --exclude-tools=<tools> --add-skills=<skills>\n  --policy=<name> (a /spawn-policy defined policy)\n  --explain (resolve and print the policy, spawn nothing)")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruTaskSpec spec = NaruTaskSpec.of()
                        .parentId(task.id());
                List<String> stmts = new ArrayList<>();
                final NaruSpawnContract[] targetContract = {null};
                final boolean[] contractWarned = {false};
                final boolean[] explain = {false};
                final boolean[] failed = {false};
                cmdLine.matcher()
                        .when("--fork").asFlag(a -> {
                            if (a.booleanValue()) {
                                spec.strategy(NaruSpawnStrategy.FORK);
                            }
                        })
                        .when("--summary").asFlag(a -> {
                            if (a.booleanValue()) {
                                spec.strategy(NaruSpawnStrategy.SUMMARY);
                            }
                        })
                        .when("--window").asEntry(a -> {
                            String v = a.stringValue();
                            if (v == null) {
                                fail(task, failed, "--window needs a value like --window=6turns");
                                return;
                            }
                            int turns = parseWindowTurns(v);
                            if (turns <= 0) {
                                fail(task, failed, "invalid --window value '%s' (expected e.g. 6turns)", v);
                                return;
                            }
                            spec.strategy(NaruSpawnStrategy.WINDOW);
                            spec.windowTurns(turns);
                        })
                        .when("--inherit").asEntry(a -> {
                            String v = a.stringValue();
                            List<NaruSpawnInherit> kinds = parseInherits(v);
                            if (kinds == null) {
                                fail(task, failed, "invalid --inherit value '%s' (expected tags, env or no,env)", v);
                                return;
                            }
                            spec.inherit(kinds.toArray(new NaruSpawnInherit[0]));
                        })
                        .when("--add-tags").asEntry(a -> spec.addTags(splitList(a.stringValue())))
                        .when("--revoke-tags").asEntry(a -> spec.revokeTags(splitList(a.stringValue())))
                        .when("--exclude-tools").asEntry(a -> spec.excludeTools(splitList(a.stringValue())))
                        .when("--add-skills").asEntry(a -> spec.addSkills(splitList(a.stringValue())))
                        .when("--policy").asEntry(a -> spec.policy(a.stringValue()))
                        .when("--explain").asFlag(a -> explain[0] = a.booleanValue())
                        .whenNonOption().asArg(a -> {
                            String s = a.image();
                            NOptional<NaruSpawnTargets.Resolved> target = NaruSpawnTargets.resolve(task.session(), task, s);
                            if (target.isNotPresent()) {
                                fail(task, failed, "Error statement: routine (or agent .md) not found %s", s);
                                return;
                            }
                            NaruSpawnTargets.Resolved r = target.get();
                            if (r.spawnKind() != null) {
                                spec.spawnKind(r.spawnKind());
                            }
                            stmts.addAll(r.statements());
                            if (r.isAgent() && (r.contract() == null || r.contract().isEmpty())) {
                                fail(task, failed, "agent file '%s' has no spawn contract in its front-matter "
                                        + "(expected { requires: \"...\", skills: [...] })", r.agentPath());
                                return;
                            }
                            if (r.contract() != null && !r.contract().isEmpty()) {
                                recordContract(targetContract, contractWarned, task, r.contract());
                            }
                        })
                        .requireAll();
                if (failed[0]) {
                    return NaruStmtResult.ofError("invalid /start arguments");
                }
                if (!stmts.isEmpty()) {
                    spec.statements(stmts);
                }
                if (targetContract[0] != null && spec.contract() == null) {
                    spec.contract(targetContract[0]);
                }
                warnIfSkillsRequestedWithoutExtension(task, spec, targetContract[0]);
                spec.resolveName();
                if (explain[0]) {
                    return explain(task, spec);
                }
                NaruTask tt = task.session().newTask(spec)
                        .bg()
                        .unhold();
                return NaruStmtResult.ofSuccess(tt.id());
            }
        });
    }

    private static void recordContract(NaruSpawnContract[] holder, boolean[] warned, NaruTask task, NaruSpawnContract c) {
        if (holder[0] == null) {
            holder[0] = c;
        } else if (!warned[0]) {
            warned[0] = true;
            task.log(NaruLogMode.SCRIPT, NMsg.ofC("⚠ multiple spawn targets declare contracts; using the first one only"));
        }
    }

    /**
     * Logs the error and flags the call as failed. The call-site handlers that parse flags
     * run as matcher callbacks, where a plain {@code return} only ends the callback — the
     * directive would then proceed to spawn a child despite the error. Flagging the failure
     * and checking it after {@code requireAll()} is what turns "printed an error and then
     * spawned anyway" into "reported the error and spawned nothing".
     */
    private static void fail(NaruTask task, boolean[] failed, String format, Object... args) {
        failed[0] = true;
        task.throwError(NMsg.ofC(format, args));
    }

    /**
     * A spawn request for skills that no skills extension will load is a silent
     * degradation: the resolution records the seeds and the event reports them, but
     * nothing applies them. Called at the call site, where the caller's intent is known.
     */
    private static void warnIfSkillsRequestedWithoutExtension(NaruTask task, NaruTaskSpec spec,
                                                              NaruSpawnContract contract) {
        java.util.Set<String> requested = new java.util.LinkedHashSet<>(spec.addSkills());
        if (contract != null) {
            requested.addAll(contract.skills());
        }
        if (requested.isEmpty()) {
            return;
        }
        boolean installed = task.session().registry().sessionExtensions().stream()
                .anyMatch(e -> "skills".equals(e.name()));
        if (!installed) {
            task.log(NaruLogMode.SCRIPT, NMsg.ofC(
                    "⚠ %s requests skills (%s), but no 'skills' extension is installed in this session; "
                            + "the spawn resolves and records them, but nothing will load them into the child",
                    spec.spawnKind() == null ? "--add-skills" : "the spawn target",
                    String.join(", ", requested)));
        }
    }

    private NaruStmtResult explain(NaruTask task, NaruTaskSpec spec) {
        NaruSpawnResolution resolution = task.session().resolveSpawn(spec);
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", "spawn policy for this target:"));
        for (String line : resolution.provenanceText()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", "  " + line));
        }
        return NaruStmtResult.ofSuccess(null);
    }

    /**
     * Parses {@code 6turns} / {@code 6} / {@code 6 turn}. Returns 0 for garbage.
     */
    private static int parseWindowTurns(String value) {
        String v = value.trim().toLowerCase().replace("turns", "").replace("turn", "").trim();
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Parses {@code tags}, {@code env}, {@code tags,env}, {@code all}. Returns null for an
     * unknown kind.
     */
    private static List<NaruSpawnInherit> parseInherits(String value) {
        List<NaruSpawnInherit> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.equalsIgnoreCase("all")) {
                out.add(NaruSpawnInherit.TAGS);
                out.add(NaruSpawnInherit.ENV);
                continue;
            }
            NOptional<NaruSpawnInherit> k = NaruSpawnInherit.parse(p);
            if (k.isNotPresent()) {
                return null;
            }
            if (!out.contains(k.get())) {
                out.add(k.get());
            }
        }
        return out;
    }

    private static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        if (value != null) {
            for (String part : value.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        return out;
    }
}