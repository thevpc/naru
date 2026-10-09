package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NStringBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * {@code /skill} — manage the skills active for the current task.
 * <p>
 * Ships in the {@code naru-skills} jar alongside the extension that owns the state, so the
 * command disappears together with the feature.
 * <p>
 * The unified {@code list} shows every available skill with its per-task state
 * (ADVERTISED or LOADED), origin, visibility, shadowing, and requires-status. {@code doctor}
 * is the report that surfaces silent problems — missing, empty, or silently changed
 * selected skills, unmet requirements, and unmapped {@code allowed-tools} — and
 * {@code reload} is the deliberate act that re-reads disk changes into the snapshot.
 */
public class NaruSkillDirective extends NaruDirectiveBase {
    public NaruSkillDirective() {
        super("skills", "ai", "manage AI skills", "skill");
        noCommand("list");
        register(new AbstractSubCommand("list", NText.ofPlain("list skills and their per-task state")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return list(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("available", NText.ofPlain("alias of list")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return list(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("show", NText.ofPlain("list skill content"),
                new SubCommandHelp("<name> [<n1>-<n2>]", "show skill named <name> content wile listing only the selected files (or all if no filter)")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing skill name : %s", name);
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruSkill skill = NaruSkillsExtension.skills(task.session()).skills().findSkill(task, name);
                if (skill == null) {
                    return notFound(task, name);
                }
                String content = String.join("\n", skill.getLines());
                List<NaruUtils.LineRange> lineRanges = NaruUtils.parseRanges(cmdLine);
                NaruUtils.showItemsWithFormat(content, "markdown", lineRanges, task);
                return NaruStmtResult.ofSuccess(null);
            }
        });
        register(new AbstractSubCommand("load", NText.ofPlain("load skill by name"),
                new SubCommandHelp("<name>", "load skill named <name>")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing skill name : %s", name);
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
                if (ext.load(task, name)) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded skill : %s", name));
                    task.addHistory(NaruMessage.user(NMsg.ofC("Loaded skill : %s", name).toString()));
                    return NaruStmtResult.ofSuccess(null);
                }
                if (!ext.exists(task, name)) {
                    return notFound(task, name);
                }
                // already loaded
                NMsg msg = NMsg.ofC("skill already loaded : %s", name);
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                return NaruStmtResult.ofSuccess(msg.toString());
            }
        });
        register(new AbstractSubCommand("unload", NText.ofPlain("unload skill by name"),
                new SubCommandHelp("<name>", "unload skill named <name>")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing skill name : %s", name);
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
                if (ext.unload(task, name)) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Unloaded skill : %s", name));
                    task.addHistory(NaruMessage.user(NMsg.ofC("Unloaded skill : %s", name).toString()));
                    task.log(NaruLogMode.PROGRESS, NMsg.ofC("Unloaded skill context. Back to main."));
                    return NaruStmtResult.ofSuccess(null);
                }
                if (!ext.exists(task, name)) {
                    return notFound(task, name);
                }
                NMsg msg = NMsg.ofC("skill not loaded : %s", name);
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                return NaruStmtResult.ofSuccess(msg.toString());
            }
        });
        register(new AbstractSubCommand("reload", NText.ofPlain("re-read a skill (or all skills) from disk"),
                new SubCommandHelp("[<name>]", "reload the named skill, or the whole discovery snapshot when no name is given")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    ext.reload();
                    int n = ext.skills().available().size();
                    NMsg msg = NMsg.ofC("Reloaded skills from disk : %s available", n);
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofSuccess(null);
                }
                NaruSkill refreshed = ext.reload(name);
                if (refreshed == null) {
                    NMsg msg = NMsg.ofC("skill not found after reload : %s", name);
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NMsg msg = NMsg.ofC("Reloaded skill : %s (hash %s)", refreshed.getName(), refreshed.getContentHash());
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                return NaruStmtResult.ofSuccess(null);
            }
        });
        register(new AbstractSubCommand("doctor", NText.ofPlain("report problems with the selected skills"),
                new SubCommandHelp("", "report missing, empty, silently changed, unsatisfied-requires, and unmapped allowed-tools")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
                Set<String> loaded = ext.loadedNames(task);
                NStringBuilder sb = NStringBuilder.of();
                if (loaded.isEmpty()) {
                    NMsg msg = NMsg.ofC("No skills loaded — nothing to check");
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofSuccess(null);
                }
                int issues = 0;
                for (String name : loaded) {
                    List<String> problems = doctorOne(ext, task, name);
                    issues += problems.size();
                    task.log(NaruLogMode.AGENT_RESPONSE,
                            NMsg.ofC("skill %s : %s", name,
                                    problems.isEmpty() ? "ok" : String.join(" ; ", problems)));
                }
                NMsg msg = NMsg.ofC("Doctor: %s selected skills, %s issue(s) found", loaded.size(), issues);
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                sb.println(msg.toString());
                return NaruStmtResult.ofSuccess(sb.toString());
            }
        });
        register(new AbstractSubCommand("trust", NText.ofPlain("trust a foreign skill root so it is read"),
                new SubCommandHelp("<index|substring>", "trust the foreign root at the index /skill list prints, or whose path/label contains the given text")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return setTrust(context, cmdLine, true);
            }
        });
        register(new AbstractSubCommand("untrust", NText.ofPlain("stop trusting a foreign skill root"),
                new SubCommandHelp("<index|substring>", "untrust a previously trusted foreign root; NARU-native roots are never trustable")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return setTrust(context, cmdLine, false);
            }
        });
    }

    private static NaruStmtResult setTrust(NaruDirectiveCallContext context, NCmdLine cmdLine, boolean trusted) {
        NaruTask task = context.task();
        NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
        String selector = cmdLine.next().map(x -> x.image()).orElse("");
        if (selector.isEmpty()) {
            NMsg msg = NMsg.ofC("missing root selector (index or path fragment)");
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        NaruSkillRoot root = resolveRoot(ext.roots(task), selector);
        if (root == null) {
            NMsg msg = NMsg.ofC("no skill root matches '%s'", selector).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        if (!root.requiresTrust()) {
            NMsg msg = NMsg.ofC("root %s is NARU-native and always trusted; nothing to change", root.path());
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofSuccess(msg.toString());
        }
        boolean changed = ext.trust(root, trusted);
        NMsg msg = NMsg.ofC("%s foreign root %s (%s)%s",
                trusted ? "Trusted" : "Untrusted",
                root.label(),
                root.path(),
                changed ? "" : " — no change");
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        return NaruStmtResult.ofSuccess(msg.toString());
    }

    /**
     * Resolves a root selector: a 1-based index into the listing, or the first root whose
     * label/path contains the text. Returns null when nothing matches.
     */
    private static NaruSkillRoot resolveRoot(List<NaruSkillRoot> roots, String selector) {
        try {
            int index = Integer.parseInt(selector.trim());
            if (index >= 1 && index <= roots.size()) {
                return roots.get(index - 1);
            }
            return null;
        } catch (NumberFormatException ignored) {
            // not an index: fall through to substring matching
        }
        String needle = selector.trim().toLowerCase();
        for (NaruSkillRoot root : roots) {
            String label = root.label() == null ? "" : root.label().toLowerCase();
            String path = root.path() == null ? "" : root.path().toString().toLowerCase();
            if (label.contains(needle) || path.contains(needle)) {
                return root;
            }
        }
        return null;
    }

    private static List<String> doctorOne(NaruSkillsExtension ext, NaruTask task, String name) {
        List<String> problems = new ArrayList<>();
        NaruSkill snapshot = ext.skills().findSkill(task, name);
        NaruSkill current = ext.skills().read(name);
        if (current == null) {
            if (snapshot == null) {
                problems.add("MISSING: neither the snapshot nor the disk has it (loaded from a file that is gone)");
            } else {
                problems.add("MISSING: the file is no longer on disk (loaded from " + snapshot.getSourceName() + ")");
            }
            return problems;
        }
        if (current.isEmpty()) {
            problems.add("EMPTY: the file has no body");
        }
        if (snapshot != null && !snapshot.getContentHash().equals(current.getContentHash())) {
            problems.add("CHANGED: content differs from the loaded snapshot (run /skill reload to apply)");
        }
        NaruRequiresStatus rs = ext.requiresStatus(current, task);
        if (rs == NaruRequiresStatus.UNSATISFIED) {
            problems.add("requires " + current.getRequires() + " is not satisfied by this task's tags: "
                    + current.getRequires().violations(task.findToolTagNames()));
        } else if (rs == NaruRequiresStatus.UNSATISFIABLE) {
            problems.add("requires " + current.getRequires() + " references tags no provider declares: "
                    + unregistered(current, task));
        }
        Set<String> toolNames = task.findTools().stream()
                .map(NaruToolDefinition::getName)
                .collect(Collectors.toSet());
        for (String token : current.getAllowedTools()) {
            if (!toolMapped(token, toolNames)) {
                problems.add("allowed-tools '" + token + "' is not mapped to any tool this task can call");
            }
        }
        problems.addAll(current.getWarnings());
        return problems;
    }

    private static Set<String> unregistered(NaruSkill skill, NaruTask task) {
        Set<String> refs = new TreeSet<>(skill.getRequires().positiveTagNames());
        refs.addAll(skill.getRequires().negativeTagNames());
        Set<String> out = new TreeSet<>();
        for (String tag : refs) {
            if (task.session().registry().findAvailableTag(tag).isEmpty()) {
                out.add(tag);
            }
        }
        return out;
    }

    /**
     * Maps one {@code allowed-tools} token against the tool names the task can call. The
     * open standard permits capability syntax like {@code Bash(git:*)}; the base name
     * before the parenthesis is what is matched.
     */
    private static boolean toolMapped(String token, Set<String> toolNames) {
        if (toolNames.stream().anyMatch(n -> n.equalsIgnoreCase(token))) {
            return true;
        }
        int paren = token.indexOf('(');
        if (paren > 0) {
            String base = token.substring(0, paren).trim();
            if (!base.isEmpty() && toolNames.stream().anyMatch(n -> n.equalsIgnoreCase(base))) {
                return true;
            }
        }
        return false;
    }

    private static NaruStmtResult list(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
        List<NaruSkillEntry> entries = ext.entries(task);
        List<NaruSkillRoot> roots = ext.roots(task);

        // distinct names, because a shadowed copy is the same skill seen from a weaker root
        Set<String> names = new TreeSet<>();
        for (NaruSkillEntry e : entries) {
            names.add(e.skill().getName());
        }
        int loaded = 0;
        for (String name : names) {
            if (ext.state(task, name) == NaruSkillState.LOADED) {
                loaded++;
            }
        }
        int advertised = names.size() - loaded;
        NStringBuilder sb = NStringBuilder.of();
        NMsg msg = NMsg.ofC("%s skills available (%s loaded, %s advertised)", names.size(), loaded, advertised);
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        sb.println(msg.toString());

        // the ordered root model: untrusted foreign roots are shown so they can be trusted
        sb.println("roots (strongest first):");
        int rootIndex = 1;
        for (NaruSkillRoot root : roots) {
            sb.println(rootRow(rootIndex++, root, task));
        }

        // every copy, losers included and marked: a NARU-native skill that shadowed a
        // foreign one stays visible instead of silently winning
        int index = 1;
        for (NaruSkillEntry entry : entries) {
            sb.println(row(index++, task, ext, entry));
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private static String rootRow(int index, NaruSkillRoot root, NaruTask task) {
        String trust;
        if (!root.requiresTrust()) {
            trust = "native";
        } else if (root.trusted()) {
            trust = "trusted";
        } else {
            trust = "untrusted";
        }
        String state = root.exists() ? "" : " (absent)";
        return String.format("[%2d] %-8s %-14s %-9s %s%s",
                index,
                root.kind().id(),
                root.label(),
                trust,
                abridgeRoot(root, task),
                state);
    }

    private static String abridgeRoot(NaruSkillRoot root, NaruTask task) {
        if (root.path() == null) {
            return "?";
        }
        String path = root.path().toString();
        String project = task.projectDir() == null ? null : task.projectDir().toString();
        if (project != null && path.startsWith(project)) {
            String rel = path.substring(project.length());
            while (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            return rel.isEmpty() ? path : rel;
        }
        return path;
    }

    private static String row(int index, NaruTask task, NaruSkillsExtension ext, NaruSkillEntry entry) {
        NaruSkill skill = entry.skill();
        NaruSkillState state = ext.state(task, skill.getName());
        NaruRequiresStatus rs = ext.requiresStatus(skill, task);
        String origin = originOf(skill, task);
        String shadow = entry.shadowed() ? "shadowed" : "-";
        return String.format("[%2d] %-11s %-22s %-8s %-9s %-16s %s",
                index,
                state.name(),
                origin,
                skill.getVisibility().name().toLowerCase(),
                shadow,
                requiresLabel(rs),
                skill.getName());
    }

    private static String requiresLabel(NaruRequiresStatus rs) {
        return switch (rs) {
            case NONE -> "requires=none";
            case SATISFIED -> "requires=ok";
            case UNSATISFIED -> "requires=unsatisfied";
            case UNSATISFIABLE -> "requires=unsatisfiable";
        };
    }

    private static String originOf(NaruSkill skill, NaruTask task) {
        String origin = skill.getOriginRoot();
        if (origin == null) {
            return "?";
        }
        String project = task.projectDir().toString();
        if (origin.startsWith(project)) {
            String rel = origin.substring(project.length());
            while (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            return rel.isEmpty() ? origin : rel;
        }
        return origin;
    }

    private static NaruStmtResult notFound(NaruTask task, String name) {
        NMsg msg = NMsg.ofC("skill not found : %s", name).asError();
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        task.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
        return NaruStmtResult.ofError(msg.toString());
    }
}