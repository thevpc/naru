package net.thevpc.naru.ext.tools.tasks;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.spawn.NaruSpawnInherit;
import net.thevpc.naru.api.spawn.NaruSpawnPolicy;
import net.thevpc.naru.api.spawn.NaruSpawnStrategy;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NRef;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /spawn-policy <name> [flags]} — defines a named spawn policy for
 * {@code /start --policy=&lt;name&gt;}.
 *
 * <pre>
 * /spawn-policy review-safe --inherit=tags --revoke-tags=write,exec --add-skills=code-review
 * </pre>
 *
 * A policy is an in-memory bundle of spawn seeds applied after the extension/spawn-kind
 * defaults and before the call-site flags (flag additions win over policy revocations,
 * like any add on top). It is re-declared by whatever script defines it on every session
 * start; there is deliberately no file format to invent.
 */
public class NaruSpawnPolicyDirective extends NaruDirectiveBase {

    public NaruSpawnPolicyDirective() {
        super("spawn-policy", "spawn", "define a named spawn policy");
        register(new AbstractSubCommand(new SubCommandHelp("<name> [flags]",
                "define a named spawn policy applied by /start --policy=<name>\n"
                        + "flags:\n"
                        + "  --strategy=<fork|summary|window|none> [--window=<n>turns]\n"
                        + "  --inherit=tags[,env]\n"
                        + "  --add-tags=<tags> --revoke-tags=<tags>\n"
                        + "  --exclude-tools=<tools> --add-skills=<skills>\n"
                        + "  --model=<name> --working-dir=<path> --prompt-mode=<mode>")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NRef<String> name = NRef.of();
                NRef<NaruSpawnStrategy> strategy = NRef.of();
                NRef<Integer> window = NRef.of();
                List<NaruSpawnInherit> inherit = new ArrayList<>();
                List<String> addTags = new ArrayList<>();
                List<String> revokeTags = new ArrayList<>();
                List<String> excludeTools = new ArrayList<>();
                List<String> addSkills = new ArrayList<>();
                NRef<String> model = NRef.of();
                NRef<NPath> workingDir = NRef.of();
                NRef<NaruPromptMode> promptMode = NRef.of();
                cmdLine.matcher()
                        .when("--strategy").asEntry(a -> {
                            String v = a.stringValue();
                            NaruSpawnStrategy s = v == null ? null : NaruSpawnStrategy.parse(v).orNull();
                            if (s == null && v != null && v.trim().startsWith("window")) {
                                s = NaruSpawnStrategy.WINDOW;
                            }
                            if (s == null) {
                                task.throwError(NMsg.ofC("invalid --strategy value '%s' (expected fork, summary, window or none)", v));
                                return;
                            }
                            strategy.set(s);
                        })
                        .when("--window").asEntry(a -> window.set(parseWindowTurns(a.stringValue())))
                        .when("--inherit").asEntry(a -> {
                            List<NaruSpawnInherit> kinds = parseInherits(a.stringValue());
                            if (kinds == null) {
                                task.throwError(NMsg.ofC("invalid --inherit value '%s' (expected tags, env or tags,env)", a.stringValue()));
                                return;
                            }
                            inherit.clear();
                            inherit.addAll(kinds);
                        })
                        .when("--add-tags").asEntry(a -> addTags.addAll(splitList(a.stringValue())))
                        .when("--revoke-tags").asEntry(a -> revokeTags.addAll(splitList(a.stringValue())))
                        .when("--exclude-tools").asEntry(a -> excludeTools.addAll(splitList(a.stringValue())))
                        .when("--add-skills").asEntry(a -> addSkills.addAll(splitList(a.stringValue())))
                        .when("--model").asEntry(a -> model.set(a.stringValue()))
                        .when("--working-dir").asEntry(a -> workingDir.set(NPath.of(a.stringValue()).toAbsolute(task.workingDir())))
                        .when("--prompt-mode").asEntry(a -> {
                            if (a.stringValue() == null) {
                                task.throwError(NMsg.ofC("--prompt-mode needs a value"));
                                return;
                            }
                            NOptional<NaruPromptMode> mode = task.session().registry().mode(a.stringValue());
                            if (mode.isNotPresent()) {
                                task.throwError(NMsg.ofC("unknown prompt mode '%s'", a.stringValue()));
                                return;
                            }
                            promptMode.set(mode.get());
                        })
                        .whenNonOption().asArg(a -> name.set(a.image()))
                        .requireAll();
                if (name.get() == null || name.get().trim().isEmpty()) {
                    task.throwError(NMsg.ofC("/spawn-policy needs a name"));
                    return NaruStmtResult.ofError("spawn policy needs a name");
                }
                NaruSpawnPolicy policy = new NaruSpawnPolicy(name.get().trim());
                if (strategy.get() != null) {
                    policy.strategy(strategy.get());
                }
                if (window.get() != null) {
                    policy.windowTurns(window.get());
                }
                if (!inherit.isEmpty()) {
                    policy.inherit(inherit.toArray(new NaruSpawnInherit[0]));
                }
                if (!addTags.isEmpty()) {
                    policy.addTags(addTags);
                }
                if (!revokeTags.isEmpty()) {
                    policy.revokeTags(revokeTags);
                }
                if (!excludeTools.isEmpty()) {
                    policy.excludeTools(excludeTools);
                }
                if (!addSkills.isEmpty()) {
                    policy.addSkills(addSkills);
                }
                if (model.get() != null) {
                    policy.model(model.get());
                }
                if (workingDir.get() != null) {
                    policy.workingDir(workingDir.get());
                }
                if (promptMode.get() != null) {
                    policy.promptMode(promptMode.get());
                }
                task.session().defineSpawnPolicy(policy);
                task.log(NaruLogMode.SCRIPT, NMsg.ofC("✓ spawn policy '%s' defined (%s)", policy.name(), policy));
                return NaruStmtResult.ofSuccess(policy.name());
            }
        });
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