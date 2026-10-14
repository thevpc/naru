package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
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
 * The two listings split by per-task state: {@code list} shows the skills loaded for the
 * task, {@code available} shows the discovered skills that are still advertised (not
 * loaded). Both print the description and the origin family ({@code naru} for native
 * roots, {@code claude}/{@code agents}/{@code opencode} for foreign ones), and both accept
 * a {@code --source=} family filter plus a keyword ({@code --search=} or a positional word)
 * that matches the name and description; {@code --semantic} ranks by TF-IDF relevance
 * instead of a substring test, and {@code --all} shows both states at once. {@code doctor}
 * is the report that surfaces silent problems — missing, empty, or silently changed
 * selected skills, unmet requirements, and unmapped {@code allowed-tools} — and
 * {@code reload} is the deliberate act that re-reads disk changes into the snapshot.
 */
public class NaruSkillDirective extends NaruDirectiveBase {
    public NaruSkillDirective() {
        super("skills", "ai", "manage AI skills", "skill");
        noCommand("list");
        register(new AbstractSubCommand("list", NText.ofPlain("list the skills loaded for this task"),
                new SubCommandHelp("[<keyword>] [--source=<native|naru|claude|agents|opencode>] [--search=<keyword>] [--semantic] [--all]",
                        "list the loaded skills with their description and origin; filter by origin family and by a name/description keyword, or rank by relevance with --semantic")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return renderList(context, cmdLine, ListMode.LOADED);
            }
        });
        register(new AbstractSubCommand("available", NText.ofPlain("list the skills that are not loaded yet"),
                new SubCommandHelp("[<keyword>] [--source=<native|naru|claude|agents|opencode>] [--search=<keyword>] [--semantic] [--all]",
                        "list the discovered skills that are still advertised (not loaded) with their description and origin; same filters as list")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return renderList(context, cmdLine, ListMode.UNLOADED);
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
                NaruSkillTrustLevel shortfall = ext.trustShortfall(task, name);
                if (shortfall != null) {
                    NMsg msg = NMsg.ofC("skill %s declares tools that need %s trust, but its foreign root grants less; run /skills trust <root> --%s",
                            name, shortfall.name().toLowerCase(), shortfall.name().toLowerCase()).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
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
        register(new AbstractSubCommand("trust", NText.ofPlain("trust a foreign skill root, and how far"),
                new SubCommandHelp("<index|substring> [--read|--write|--exec]",
                        "trust the foreign root at the index /skill list prints, or whose path/label contains the given text; grant read (default), write, or exec level")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return setTrust(context, cmdLine, null);
            }
        });
        register(new AbstractSubCommand("untrust", NText.ofPlain("stop trusting a foreign skill root"),
                new SubCommandHelp("<index|substring>", "untrust a previously trusted foreign root; NARU-native roots are never trustable")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return setTrust(context, cmdLine, NaruSkillTrustLevel.NONE);
            }
        });
    }

    // ── autocomplete ─────────────────────────────────────────────────────────

    /** Flags the {@code list} / {@code available} listings accept. */
    private static final String[] LIST_FLAGS = {
            "--source=", "--search=", "--semantic", "--all"
    };

    /**
     * Tab-completion for the value words of each subcommand: skill names for the
     * name-taking commands, root labels for {@code trust}/{@code untrust} (plus its
     * {@code --read|--write|--exec} levels). The {@code list}/{@code available}
     * listings complete their {@code --source=}, {@code --search=}, {@code --semantic}
     * and {@code --all} options, the family values after {@code --source=}, and skill
     * names as the positional keyword. Subcommand names are delegated to the base
     * resolver.
     */
    @Override
    public NArgCompleteResult resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos, NaruSession session) {
        String[] words = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        if (wordIndex < 2 || words.length < 2) {
            return super.resolveCandidates(cmdLine, pos, session);
        }
        String currentArg = wordIndex < words.length ? words[wordIndex] : "";
        String sub = words[1];
        List<NArgCompleteCandidate> candidates = new ArrayList<>();
        switch (sub) {
            case "list":
            case "available":
                listingCandidates(candidates, currentArg, session);
                break;
            case "show":
            case "load":
            case "unload":
            case "reload":
                addSkillNames(candidates, currentArg, session);
                break;
            case "trust":
                if (currentArg.startsWith("-")) {
                    addCandidates(candidates, currentArg, "--read", "--write", "--exec");
                } else {
                    addRootLabels(candidates, currentArg, session);
                }
                break;
            case "untrust":
                addRootLabels(candidates, currentArg, session);
                break;
            default:
                break;
        }
        return NArgCompleteResult.ofCandidates(candidates);
    }

    /**
     * Completion for the listing options: flags while the word starts with {@code --},
     * the family values once it reaches {@code --source=} (including after a comma, so
     * {@code --source=claude,ag} offers {@code agents}), skill names after
     * {@code --search=}, and skill names as a bare positional keyword.
     */
    private void listingCandidates(List<NArgCompleteCandidate> candidates, String currentArg, NaruSession session) {
        if (currentArg.startsWith("--source=")) {
            String body = currentArg.substring("--source=".length());
            int comma = body.lastIndexOf(',');
            String typed = comma >= 0 ? body.substring(comma + 1) : body;
            String head = comma >= 0 ? "--source=" + body.substring(0, comma + 1) : "--source=";
            for (String family : sourceFamilies(session)) {
                if (family.toLowerCase().startsWith(typed.toLowerCase())) {
                    candidates.add(NArgCompleteCandidate.of(head + family, family));
                }
            }
            return;
        }
        if (currentArg.startsWith("--search=")) {
            addNamesWithPrefix(candidates, currentArg, "--search=", session);
            return;
        }
        if (currentArg.startsWith("-")) {
            addCandidates(candidates, currentArg, LIST_FLAGS);
            return;
        }
        addSkillNames(candidates, currentArg, session);
    }

    /** Completes available skill names by case-insensitive prefix. */
    private void addSkillNames(List<NArgCompleteCandidate> candidates, String currentArg, NaruSession session) {
        for (NaruSkill skill : NaruSkillsExtension.skills(session).skills().available()) {
            String name = skill.getName();
            if (name.toLowerCase().startsWith(currentArg.toLowerCase())) {
                candidates.add(NArgCompleteCandidate.of(name));
            }
        }
    }

    /** Completes {@code <prefix><skill-name>} values (e.g. {@code --search=javadoc}). */
    private void addNamesWithPrefix(List<NArgCompleteCandidate> candidates, String currentArg, String prefix,
                                    NaruSession session) {
        for (NaruSkill skill : NaruSkillsExtension.skills(session).skills().available()) {
            String candidate = prefix + skill.getName();
            if (candidate.toLowerCase().startsWith(currentArg.toLowerCase())) {
                candidates.add(NArgCompleteCandidate.of(candidate, skill.getName()));
            }
        }
    }

    /** Completes the labels of the discovered foreign/native roots. */
    private void addRootLabels(List<NArgCompleteCandidate> candidates, String currentArg, NaruSession session) {
        for (NaruSkillRoot root : NaruSkillsExtension.skills(session).skills().roots(null)) {
            String label = root.label();
            if (label != null && label.toLowerCase().startsWith(currentArg.toLowerCase())) {
                candidates.add(NArgCompleteCandidate.of(label));
            }
        }
    }

    /**
     * The families a {@code --source=} filter accepts: the aliases the matcher understands
     * ({@code native}/{@code naru}/{@code foreign}) followed by the labels of the roots that
     * were actually discovered (e.g. {@code claude}, {@code agents}, {@code opencode}).
     */
    private List<String> sourceFamilies(NaruSession session) {
        List<String> out = new ArrayList<>(List.of("native", "naru", "foreign"));
        for (NaruSkillRoot root : NaruSkillsExtension.skills(session).skills().roots(null)) {
            String label = root.label();
            if (label != null && !label.isBlank() && !out.contains(label)) {
                out.add(label);
            }
        }
        return out;
    }

    /**
     * {@code /skills trust <sel> [--read|--write|--exec]} and
     * {@code /skills untrust <sel>}. The level argument is {@code null} on the trust
     * command (choose from the flags, read when absent) and {@link NaruSkillTrustLevel#NONE}
     * on untrust.
     */
    private static NaruStmtResult setTrust(NaruDirectiveCallContext context, NCmdLine cmdLine, NaruSkillTrustLevel level) {
        NaruTask task = context.task();
        NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
        String selector = null;
        NaruSkillTrustLevel granted = level;
        while (!cmdLine.isEmpty()) {
            NArg a = cmdLine.next().get();
            String image = a.image();
            if (image.startsWith("--")) {
                String flag = image.substring(2).trim();
                if (granted == NaruSkillTrustLevel.NONE) {
                    NMsg msg = NMsg.ofC("unexpected flag --%s on untrust", flag).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruSkillTrustLevel byFlag = parseTrustFlag(flag);
                if (byFlag == null) {
                    NMsg msg = NMsg.ofC("unknown trust level --%s (expected --read, --write or --exec)", flag).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                granted = byFlag;
            } else if (selector == null) {
                selector = image;
            }
        }
        if (selector == null || selector.isEmpty()) {
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
        NaruSkillTrustLevel grant = granted == null ? NaruSkillTrustLevel.READ : granted;
        boolean changed = ext.trust(root, grant);
        String verb = grant == NaruSkillTrustLevel.NONE ? "Untrusted" : "Trusted";
        NMsg msg = grant == NaruSkillTrustLevel.NONE
                ? NMsg.ofC("%s foreign root %s (%s)%s", verb, root.label(), root.path(),
                        changed ? "" : " — no change")
                : NMsg.ofC("%s foreign root %s (%s) at %s level%s", verb, root.label(), root.path(),
                        grant.name().toLowerCase(), changed ? "" : " — no change");
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        return NaruStmtResult.ofSuccess(msg.toString());
    }

    private static NaruSkillTrustLevel parseTrustFlag(String flag) {
        return switch (flag) {
            case "read" -> NaruSkillTrustLevel.READ;
            case "write" -> NaruSkillTrustLevel.WRITE;
            case "exec" -> NaruSkillTrustLevel.EXEC;
            default -> null;
        };
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
        NaruSkillTrustLevel shortfall = ext.trustShortfall(task, name);
        if (shortfall != null) {
            problems.add("TRUST: declares tools that need " + shortfall.name().toLowerCase()
                    + " trust, but its foreign root grants less");
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

    // ── listing (loaded / available) ────────────────────────────────────────

    private enum ListMode {
        LOADED, UNLOADED, ALL
    }

    private static final class ListOptions {
        String query = "";
        final List<String> sources = new ArrayList<>();
        boolean semantic;
        boolean all;
        String error;
    }

    private static NaruStmtResult renderList(NaruDirectiveCallContext context, NCmdLine cmdLine, ListMode mode) {
        NaruTask task = context.task();
        NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
        ListOptions opts = parseListOptions(cmdLine);
        if (opts.error != null) {
            NMsg msg = NMsg.ofC(opts.error).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }

        List<NaruSkill> available = ext.skills().available(task);
        int loaded = 0;
        int unloaded = 0;
        List<NaruSkill> candidates = new ArrayList<>();
        for (NaruSkill skill : available) {
            boolean isLoaded = ext.state(task, skill.getName()) == NaruSkillState.LOADED;
            if (isLoaded) {
                loaded++;
            } else {
                unloaded++;
            }
            if (!accepts(mode, opts.all, isLoaded)) {
                continue;
            }
            if (!opts.sources.isEmpty() && !matchesSource(skill, opts.sources)) {
                continue;
            }
            candidates.add(skill);
        }
        List<NaruSkillSearch.Hit> hits = NaruSkillSearch.search(candidates, opts.query, opts.semantic);
        boolean filtered = !opts.sources.isEmpty() || !opts.query.isBlank();

        NStringBuilder sb = NStringBuilder.of();
        NMsg header = NMsg.ofC(headerText(mode, loaded, unloaded, available.size(), opts, filtered));
        task.log(NaruLogMode.AGENT_RESPONSE, header);
        sb.println(header.toString());

        if (hits.isEmpty()) {
            sb.println(emptyText(mode, loaded, unloaded, available.size(), filtered));
        } else {
            int index = 1;
            for (NaruSkillSearch.Hit hit : hits) {
                sb.println(skillRow(index++, task, ext, hit, opts.semantic));
            }
        }

        // the ordered root model: untrusted foreign roots are shown so they can be trusted
        List<NaruSkillRoot> roots = ext.roots(task);
        if (!roots.isEmpty()) {
            sb.println("roots (strongest first):");
            int rootIndex = 1;
            for (NaruSkillRoot root : roots) {
                sb.println(rootRow(rootIndex++, root, task));
            }
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private static boolean accepts(ListMode mode, boolean all, boolean isLoaded) {
        if (all || mode == ListMode.ALL) {
            return true;
        }
        return (mode == ListMode.LOADED) == isLoaded;
    }

    private static String headerText(ListMode mode, int loaded, int unloaded, int total,
                                     ListOptions opts, boolean filtered) {
        ListMode effective = opts.all ? ListMode.ALL : mode;
        String head = switch (effective) {
            case LOADED -> String.format("%d loaded skills (of %d available)", loaded, total);
            case UNLOADED -> String.format("%d skills not loaded (of %d available)", unloaded, total);
            case ALL -> String.format("%d skills (%d loaded, %d not loaded)", total, loaded, unloaded);
        };
        if (filtered) {
            List<String> parts = new ArrayList<>();
            if (!opts.sources.isEmpty()) {
                parts.add("source=" + String.join("/", opts.sources));
            }
            if (!opts.query.isBlank()) {
                parts.add((opts.semantic ? "semantic '" : "'") + opts.query + "'");
            }
            if (!parts.isEmpty()) {
                head += " matching " + String.join(", ", parts);
            }
        }
        return head;
    }

    private static String emptyText(ListMode mode, int loaded, int unloaded, int total, boolean filtered) {
        if (filtered) {
            return "no skills match the filter";
        }
        if (total == 0) {
            return "no skills available on this project";
        }
        return switch (mode) {
            case LOADED -> "no skills loaded yet (use /skills available to see " + unloaded + " not loaded)";
            case UNLOADED -> "all " + total + " skill(s) are loaded";
            case ALL -> "no skills available on this project";
        };
    }

    private static String skillRow(int index, NaruTask task, NaruSkillsExtension ext,
                                   NaruSkillSearch.Hit hit, boolean semantic) {
        NaruSkill skill = hit.skill();
        NaruSkillState state = ext.state(task, skill.getName());
        NaruRequiresStatus rs = ext.requiresStatus(skill, task);
        String score = semantic
                ? String.format(java.util.Locale.ROOT, "%-6s", String.format(java.util.Locale.ROOT, "%.2f", hit.score()))
                : "";
        String name = skill.getName() + (skill.isShadowed() ? " (shadowed)" : "");
        String row = String.format("[%2d] %s%-9s %-8s %-8s %-22s %s",
                index,
                score,
                state.name(),
                NaruSkillSearch.sourceLabel(skill),
                skill.getVisibility().name().toLowerCase(),
                requiresLabel(rs),
                name);
        String desc = oneLine(skill.getDescription());
        return desc.isEmpty() ? row : row + " — " + desc;
    }

    /** Single-line, whitespace-collapsed, length-capped description for a listing row. */
    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        while (t.contains("  ")) {
            t = t.replace("  ", " ");
        }
        if (t.length() > 140) {
            t = t.substring(0, 137) + "...";
        }
        return t;
    }

    /**
     * Parses the shared listing options. {@code --source=} may repeat or carry a
     * comma-separated list; a bare positional word (or {@code --search=}) is the keyword.
     * Values are attached with {@code =} because that is the form {@code NCmdLine} accepts.
     */
    private static ListOptions parseListOptions(NCmdLine cmdLine) {
        ListOptions o = new ListOptions();
        StringBuilder query = new StringBuilder();
        while (cmdLine != null && !cmdLine.isEmpty()) {
            NArg a = cmdLine.next().get();
            String image = a.image();
            if (image == null) {
                continue;
            }
            if (image.startsWith("--")) {
                String body = image.substring(2);
                String flag;
                String value = null;
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    flag = body.substring(0, eq);
                    value = body.substring(eq + 1);
                } else {
                    flag = body;
                    value = a.value();
                }
                switch (flag.toLowerCase()) {
                    case "source" -> {
                        if (value == null || value.isBlank()) {
                            o.error = "option --source needs a value (use --source=native|naru|claude|agents|opencode)";
                            return o;
                        }
                        for (String part : value.split(",")) {
                            if (!part.isBlank()) {
                                o.sources.add(part.trim());
                            }
                        }
                    }
                    case "search", "keyword", "query" -> {
                        if (value == null) {
                            o.error = "option --" + flag + " needs a value (use --" + flag + "=<text>)";
                            return o;
                        }
                        if (query.length() > 0) {
                            query.append(' ');
                        }
                        query.append(value);
                    }
                    case "semantic" -> o.semantic = true;
                    case "all" -> o.all = true;
                    default -> {
                        o.error = "unknown option --" + flag + " (expected --source=, --search=, --semantic or --all)";
                        return o;
                    }
                }
            } else {
                if (query.length() > 0) {
                    query.append(' ');
                }
                query.append(image);
            }
        }
        o.query = query.toString().trim();
        return o;
    }

    private static boolean matchesSource(NaruSkill skill, List<String> sources) {
        boolean foreign = skill.isForeign();
        String label = NaruSkillSearch.sourceLabel(skill).toLowerCase();
        for (String raw : sources) {
            String s = raw.trim().toLowerCase();
            if (s.isEmpty() || s.equals("all")) {
                return true;
            }
            if (s.equals("native") || s.equals("naru")) {
                if (!foreign) {
                    return true;
                }
            } else if (s.equals("foreign")) {
                if (foreign) {
                    return true;
                }
            } else if (s.equals(label)) {
                return true;
            }
        }
        return false;
    }

    private static String rootRow(int index, NaruSkillRoot root, NaruTask task) {
        String trust;
        if (!root.requiresTrust()) {
            trust = "native";
        } else if (root.trustLevel() == NaruSkillTrustLevel.NONE) {
            trust = "untrusted";
        } else {
            trust = root.trustLevel().name().toLowerCase();
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

    private static String requiresLabel(NaruRequiresStatus rs) {
        return switch (rs) {
            case NONE -> "requires=none";
            case SATISFIED -> "requires=ok";
            case UNSATISFIED -> "requires=unsatisfied";
            case UNSATISFIABLE -> "requires=unsatisfiable";
        };
    }

    private static NaruStmtResult notFound(NaruTask task, String name) {
        NMsg msg = NMsg.ofC("skill not found : %s", name).asError();
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        task.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
        return NaruStmtResult.ofError(msg.toString());
    }
}