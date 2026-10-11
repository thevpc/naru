package net.thevpc.naru.ext.tools.sessions;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.cmdline.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NStringBuilder;

import java.util.*;
import java.util.function.Function;

/**
 * {@code /session}: manage the current session and the sessions saved for this project.
 *
 * <p>Most subcommands act on the current session. They also accept {@code --id <prefix>}
 * and/or {@code --name <name>} to act on another session instead: a running session is
 * preferred, and when none matches, a session saved on disk is edited in place. Ids are
 * UUIDs and a unique prefix is enough; an ambiguous prefix resolves to nothing rather than
 * to a guess, the same rule {@code /plan} uses.
 */
public class NaruSessionDirective extends NaruDirectiveBase {
    public NaruSessionDirective() {
        super("session", "session", "manage sessions", "sessions");
        noCommand("list");
        register(new AbstractSubCommand("current", NText.ofPlain("show current session"),
                new SubCommandHelp("[--id=<prefix>] [--name=<name>]", "show one session; defaults to the current one")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeCurrent(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("list", NText.ofPlain("list saved sessions")
                , new SubCommandHelp("", "list the sessions saved for this project")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("rename", NText.ofPlain("rename a session")
                , new SubCommandHelp("<name> [--id=<prefix>|--name=<name>]", "give a session a name (or a new one) and save it; defaults to the current session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeRename(context, cmdLine);
            }
        });

        register(new AbstractSubCommand("public", NText.ofPlain("change session visibility to public"),
                new SubCommandHelp("[--id=<prefix>|--name=<name>]", "make a session public; defaults to the current session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeChangeVisibility(NaruVisibility.PUBLIC, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("private", NText.ofPlain("change session visibility to private"),
                new SubCommandHelp("[--id=<prefix>|--name=<name>]", "make a session private; defaults to the current session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeChangeVisibility(NaruVisibility.PRIVATE, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("delete", NText.ofPlain("delete session")
                ,new SubCommandHelp("<name>...", "delete session by name")
                ,new SubCommandHelp("[--id=<prefix>|--name=<name>]", "delete one session selected by id or name")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeDelete(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("purge", NText.ofPlain("purge all sessions")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executePurge(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("load", NText.ofPlain("load session by name (or path)")
                ,new SubCommandHelp("<name>...", "load session by name")
                ,new SubCommandHelp("[--id=<prefix>|--name=<name>]", "load the session selected by id or name")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeLoad(context, cmdLine);
            }
        });
//        register(new AbstractSubCommand("reload", NText.ofPlain("reload current session")
//                , new SubCommandHelp("", "re-read this session from the store, "
//                + "or start a fresh one if it was never saved. "
//                + "changes made since the last write are discarded")
//        ) {
//            @Override
//            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
//                return executeReload(context, cmdLine);
//            }
//        });
        register(new AbstractSubCommand("restore", NText.ofPlain("restore from the store"),
                new SubCommandHelp("[--id=<prefix>|--name=<name>]", "restore a running session from the store; defaults to the current session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeRestore(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("save", NText.ofPlain("save session")
            ,new SubCommandHelp("[<name>]", "save current session with optional name.\nwhen no name was provided, and this is a new session, a generated name will be guessed using the current model.\n when name is provided, it will be used to set name or rename the session.")
            ,new SubCommandHelp("[--id=<prefix>|--name=<name>]", "save a running session selected by id or name; defaults to the current session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeSave(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("new", NText.ofPlain("start a new session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeNew(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("reset", NText.ofPlain("reset current session"),
                new SubCommandHelp("[--id=<prefix>|--name=<name>]", "reset a running session; defaults to the current session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeReset(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("copy", NText.ofPlain("copy a session to a new session"),
                new SubCommandHelp("[--id=<prefix>|--name=<name>]", "copy a running session; defaults to the current session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeCopy(context, cmdLine);
            }
        });
    }

    /**
     * Accept the terse {@code -id}/{@code -name} spellings the same way as {@code --id}/
     * {@code --name}. Nuts' cmdline parser reads {@code -id} as the flags {@code -i} and
     * {@code -d}, so the alias has to be rewritten before parsing, not after.
     */
    @Override
    public NaruStmtResult execute(NaruDirectiveCallContext context) {
        final String argument = context.argument();
        final String normalized = normalizeTargetOptions(argument);
        if (normalized != null && !normalized.equals(argument)) {
            final NaruDirectiveCallContext base = context;
            context = new NaruDirectiveCallContext() {
                @Override
                public String name() {
                    return base.name();
                }

                @Override
                public String argument() {
                    return normalized;
                }

                @Override
                public NaruTask task() {
                    return base.task();
                }
            };
        }
        return super.execute(context);
    }

    private static String normalizeTargetOptions(String argument) {
        if (argument == null || (argument.indexOf("-id") < 0 && argument.indexOf("-name") < 0)) {
            return argument;
        }
        return argument
                .replaceAll("(^|\\s)-id(?=\\s|=|$)", "$1--id")
                .replaceAll("(^|\\s)-name(?=\\s|=|$)", "$1--name");
    }

    // ── session selection (--id / --name) ───────────────────────────────────────

    /**
     * The selector arguments common to every applicable subcommand: the id/name options
     * plus whatever positional words the subcommand itself takes.
     */
    private static final class TargetArgs {
        String id;
        String name;
        String error;
        final List<String> positionals = new ArrayList<>();

        boolean hasSelector() {
            return !NBlankable.isBlank(id) || !NBlankable.isBlank(name);
        }

        String selectorText() {
            List<String> parts = new ArrayList<>();
            if (!NBlankable.isBlank(id)) {
                parts.add("id=" + id);
            }
            if (!NBlankable.isBlank(name)) {
                parts.add("name=" + name);
            }
            return String.join(" ", parts);
        }
    }

    /**
     * One resolved session: its uuid, a display name, and — depending on where it was found —
     * the live session or the saved catalog entry. At least one of {@code live}/{@code saved}
     * is non-null, and {@code live} is preferred when both exist.
     */
    private static final class SessionTarget {
        final String uuid;
        final String name;
        final NaruSession live;
        final NaruResourceInfo saved;

        SessionTarget(String uuid, String name, NaruSession live, NaruResourceInfo saved) {
            this.uuid = uuid;
            this.name = name;
            this.live = live;
            this.saved = saved;
        }

        String displayName() {
            String n = name;
            if (NBlankable.isBlank(n) && saved != null) {
                n = saved.getName();
            }
            return NBlankable.isBlank(n) ? "NO_NAME" : n;
        }
    }

    private TargetArgs parseTargetArgs(NaruTask task, NCmdLine cmdLine) {
        TargetArgs ta = new TargetArgs();
        while (!cmdLine.isEmpty()) {
            NArg a = cmdLine.peek().get();
            if (a.isOption()) {
                if (a.key().equals("--id")) {
                    cmdLine.next();
                    ta.id = optionValue(cmdLine, a);
                    if (NBlankable.isBlank(ta.id)) {
                        ta.error = "missing value for " + a.image();
                        return ta;
                    }
                } else if (a.key().equals("--name")) {
                    cmdLine.next();
                    ta.name = optionValue(cmdLine, a);
                    if (NBlankable.isBlank(ta.name)) {
                        ta.error = "missing value for " + a.image();
                        return ta;
                    }
                } else {
                    ta.error = "unknown option " + a.image();
                    return ta;
                }
            } else {
                ta.positionals.add(cmdLine.next().get().image());
            }
        }
        return ta;
    }

    /**
     * The value of an option, whether it was written {@code --id=aa} or {@code --id aa}.
     * Nuts binds only the first form during parsing, so the second is consumed here.
     */
    private static String optionValue(NCmdLine cmdLine, NArg option) {
        String value = option.getStringValue().orNull();
        if (value != null) {
            return value;
        }
        if (cmdLine.hasNext()) {
            NArg next = cmdLine.peek().get();
            if (next.isNonOption()) {
                return cmdLine.next().get().image();
            }
        }
        return null;
    }

    /**
     * Resolves the target session. With no selector this is the current session; otherwise a
     * running session is preferred and a session saved on disk is the fallback. An id may be
     * any unique prefix of the uuid.
     */
    private SessionTarget resolveTarget(NaruTask task, TargetArgs ta) {
        NaruSession current = task.session();
        List<NaruResourceInfo> saved = current.sessionStoreManager().list();
        if (!ta.hasSelector()) {
            return new SessionTarget(current.uuid(), current.name(), current, findSaved(saved, current.uuid()));
        }

        Map<String, SessionTarget> all = new LinkedHashMap<>();
        addTarget(all, new SessionTarget(current.uuid(), safeName(current), current, findSaved(saved, current.uuid())));
        NaruAgent agent = current.agent();
        if (agent != null) {
            for (NaruSession s : agent.sessions()) {
                if (s == null || s.uuid() == null) {
                    continue;
                }
                addTarget(all, new SessionTarget(s.uuid(), safeName(s), s, findSaved(saved, s.uuid())));
            }
        }
        for (NaruResourceInfo info : saved) {
            if (info.getUuid() == null) {
                continue;
            }
            addTarget(all, new SessionTarget(info.getUuid(), info.getName(), null, info));
        }

        List<SessionTarget> matches = new ArrayList<>();
        for (SessionTarget t : all.values()) {
            if (!NBlankable.isBlank(ta.id) && !idMatches(t.uuid, ta.id)) {
                continue;
            }
            if (!NBlankable.isBlank(ta.name) && !nameMatches(t.name, ta.name)) {
                continue;
            }
            matches.add(t);
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("session not found: " + ta.selectorText());
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "ambiguous session " + ta.selectorText() + ": " + describeMatches(matches));
        }
        return matches.get(0);
    }

    private NaruStmtResult withTarget(NaruTask task, TargetArgs ta, Function<SessionTarget, NaruStmtResult> body) {
        SessionTarget target;
        try {
            target = resolveTarget(task, ta);
        } catch (IllegalArgumentException e) {
            return fail(task, e.getMessage());
        }
        return body.apply(target);
    }

    private NaruStmtResult fail(NaruTask task, String message) {
        NMsg msg = NMsg.ofC("%s", message).asError();
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        return NaruStmtResult.ofError(msg.toString());
    }

    private static String safeName(NaruSession session) {
        try {
            return session.name();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void addTarget(Map<String, SessionTarget> all, SessionTarget target) {
        // current/live targets are added first, so a saved-only duplicate never wins
        all.putIfAbsent(target.uuid, target);
    }

    private static NaruResourceInfo findSaved(List<NaruResourceInfo> list, String uuid) {
        for (NaruResourceInfo info : list) {
            if (Objects.equals(info.getUuid(), uuid)) {
                return info;
            }
        }
        return null;
    }

    private static boolean idMatches(String uuid, String id) {
        return uuid != null && uuid.toLowerCase().startsWith(id.trim().toLowerCase());
    }

    private static boolean nameMatches(String name, String selector) {
        return name != null && name.trim().equalsIgnoreCase(selector.trim());
    }

    private static String describeMatches(List<SessionTarget> matches) {
        List<String> parts = new ArrayList<>();
        for (SessionTarget t : matches) {
            parts.add(t.uuid + " (" + t.displayName() + ")");
        }
        return String.join(", ", parts);
    }

    /**
     * Rejects {@code --id}/{@code --name} on a subcommand that acts on every session or on
     * the current one only, instead of silently ignoring them.
     */
    private NaruStmtResult rejectTargetSelectors(NaruTask task, NCmdLine cmdLine, String subCommand) {
        while (!cmdLine.isEmpty()) {
            NArg a = cmdLine.peek().get();
            if (a.isOption() && (a.key().equals("--id") || a.key().equals("--name"))) {
                return fail(task, NMsg.ofC("/session %s does not accept %s",
                        subCommand, a.key()).toString());
            }
            cmdLine.next();
        }
        return null;
    }

    private boolean renameTarget(NaruTask task, SessionTarget target, String newName) {
        if (target.live != null) {
            target.live.name(newName);
            return true;
        }
        return task.session().sessionStoreManager().rename(target.uuid, newName);
    }

    // ── autocomplete ─────────────────────────────────────────────────────────────

    /**
     * The subcommands that accept {@code --id}/{@code --name}: completion has to offer
     * session ids/names as their values, and only for these.
     */
    private static final Set<String> TARGET_SUBCOMMANDS = Set.of(
            "current", "save", "restore", "reset", "copy",
            "public", "private", "delete", "rename", "load");

    /** Subcommands whose positional words name sessions rather than being free text. */
    private static final Set<String> POSITIONAL_IS_SESSION = Set.of("delete", "load");

    /**
     * Completion for {@code /session <sub> ...}: delegates subcommand-name completion to
     * the base class, and for a target-aware subcommand completes the id/name values
     * (whether written {@code --id=<prefix>} or {@code --id <prefix>}) from the same
     * live-plus-saved universe the command resolves against.
     */
    @Override
    public NArgCompleteResult resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos, NaruSession session) {
        String[] words = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        if (wordIndex >= 2 && words.length > 1 && TARGET_SUBCOMMANDS.contains(words[1])) {
            return targetCandidates(cmdLine, pos, session, POSITIONAL_IS_SESSION.contains(words[1]));
        }
        return super.resolveCandidates(cmdLine, pos, session);
    }

    private NArgCompleteResult targetCandidates(NCmdLine cmdLine, NArgCompletePosition pos,
                                                NaruSession session, boolean positionalIsSession) {
        List<NArgCompleteCandidate> candidates = new ArrayList<>();
        String[] words = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String currentArg = wordIndex < words.length ? words[wordIndex] : "";
        String previous = wordIndex > 0 && wordIndex - 1 < words.length ? words[wordIndex - 1] : null;

        List<String> names = new ArrayList<>();
        List<String> uuids = new ArrayList<>();
        collectSessionIds(session, names, uuids);

        if (currentArg.startsWith("--id=")) {
            addMatching(candidates, uuids, currentArg.substring("--id=".length()), "--id=");
            return NArgCompleteResult.ofCandidates(candidates);
        }
        if (currentArg.startsWith("--name=")) {
            addMatching(candidates, names, currentArg.substring("--name=".length()), "--name=");
            return NArgCompleteResult.ofCandidates(candidates);
        }
        if ("--id".equals(previous)) {
            addMatching(candidates, uuids, currentArg, "");
            return NArgCompleteResult.ofCandidates(candidates);
        }
        if ("--name".equals(previous)) {
            addMatching(candidates, names, currentArg, "");
            return NArgCompleteResult.ofCandidates(candidates);
        }
        if (currentArg.startsWith("-")) {
            addCandidates(candidates, currentArg, "--id", "--id=", "--name", "--name=");
            return NArgCompleteResult.ofCandidates(candidates);
        }
        if (positionalIsSession) {
            addMatching(candidates, names, currentArg, "");
            addMatching(candidates, uuids, currentArg, "");
        }
        addCandidates(candidates, currentArg, "--id", "--name");
        return NArgCompleteResult.ofCandidates(candidates);
    }

    /**
     * The live-plus-saved session universe, de-duplicated by uuid with the live name
     * winning. Split into names and uuids because a value typed after {@code --name} must
     * not offer uuids and vice versa.
     */
    private static void collectSessionIds(NaruSession session, List<String> names, List<String> uuids) {
        Set<String> seenNames = new LinkedHashSet<>();
        Set<String> seenUuids = new LinkedHashSet<>();
        addSessionId(session, names, uuids, seenNames, seenUuids);
        NaruAgent agent = session.agent();
        if (agent != null) {
            for (NaruSession s : agent.sessions()) {
                addSessionId(s, names, uuids, seenNames, seenUuids);
            }
        }
        for (NaruResourceInfo info : session.sessionStoreManager().list()) {
            if (info.getUuid() != null && seenUuids.add(info.getUuid()) && !info.getUuid().isEmpty()) {
                uuids.add(info.getUuid());
            }
            String n = info.getName();
            if (n != null && !n.isEmpty() && seenNames.add(n)) {
                names.add(n);
            }
        }
    }

    private static void addSessionId(NaruSession s,
                                     List<String> names, List<String> uuids,
                                     Set<String> seenNames, Set<String> seenUuids) {
        if (s == null || s.uuid() == null || s.uuid().isEmpty()) {
            return;
        }
        if (seenUuids.add(s.uuid())) {
            uuids.add(s.uuid());
        }
        String n = safeName(s);
        if (n != null && !n.isEmpty() && !"NO_NAME".equals(n) && seenNames.add(n)) {
            names.add(n);
        }
    }

    private void addMatching(List<NArgCompleteCandidate> candidates, List<String> values,
                             String prefix, String valuePrefix) {
        String p = prefix == null ? "" : prefix;
        for (String v : values) {
            if (v.toLowerCase().startsWith(p.toLowerCase())) {
                candidates.add(NArgCompleteCandidate.of(valuePrefix + v));
            }
        }
    }


    public NaruStmtResult executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruStmtResult rejected = rejectTargetSelectors(task, cmdLine, "list");
        if (rejected != null) {
            return rejected;
        }

        NaruSession current = task.session();
        List<NaruResourceInfo> saved = current.sessionStoreManager().list();
        // current first, then every other running session, then the saved-only ones. A
        // uuid is listed once: a session that is both live and saved shows its live state.
        List<NaruSession> running = new ArrayList<>();
        Set<String> runningUuids = new LinkedHashSet<>();
        NaruAgent agent = current.agent();
        if (agent != null) {
            for (NaruSession s : agent.sessions()) {
                if (s != null && s.uuid() != null && runningUuids.add(s.uuid())) {
                    running.add(s);
                }
            }
        }

        NStringBuilder sb = NStringBuilder.of();
        int index = 1;
        Set<String> shown = new LinkedHashSet<>();
        logSessionRow(task, sb, index++, current, findSaved(saved, current.uuid()), true, current.isRunning());
        shown.add(current.uuid());
        for (NaruSession s : running) {
            if (shown.contains(s.uuid())) {
                continue;
            }
            logSessionRow(task, sb, index++, s, findSaved(saved, s.uuid()), false, true);
            shown.add(s.uuid());
        }
        for (NaruResourceInfo info : saved) {
            if (info.getUuid() == null || shown.contains(info.getUuid())) {
                continue;
            }
            logSessionRow(task, sb, index++, null, info, false, false);
            shown.add(info.getUuid());
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    /**
     * One /session list row. {@code *} marks the current session, the visibility and the
     * {@code live, N tasks} / {@code saved} state make liveness explicit, and an unknown
     * age prints {@code (unknown)} rather than the empty {@code ()} a null instant used
     * to produce.
     */
    private void logSessionRow(NaruTask task, NStringBuilder sb, int index,
                               NaruSession live, NaruResourceInfo saved,
                               boolean current, boolean running) {
        String uuid = live != null ? live.uuid() : saved.getUuid();
        String name = live != null ? safeName(live) : saved.getName();
        if (NBlankable.isBlank(name)) {
            name = "NO_NAME";
        }
        NaruVisibility visibility = live != null ? live.getVisibility() : saved.getMode();
        String age = NaruUtils.timeAgo(live != null ? live.modificationInstant() : saved.getModificationInstant());
        if (NBlankable.isBlank(age)) {
            age = "unknown";
        }
        String state;
        if (running) {
            int taskCount = liveTaskCount(live);
            state = "live, " + taskCount + (taskCount == 1 ? " task" : " tasks");
        } else if (saved != null) {
            state = "saved";
        } else {
            state = "not saved";
        }
        NMsg msg = NMsg.ofC("[%s]%s %s %s %s %s (%s)",
                index,
                current ? NMsg.ofStyledPrimary1(" *") : "  ",
                NMsg.ofStyledString(name),
                NMsg.ofStyledPrimary3(uuid),
                NMsg.ofStyledKeyword(visibility == null ? "?" : visibility.name().toLowerCase()),
                NMsg.ofStyledKeyword(state),
                NMsg.ofStyledPale(age)
        );
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        sb.println(msg.toString());
    }

    private static int liveTaskCount(NaruSession session) {
        if (session == null) {
            return 0;
        }
        try {
            List<NaruTask> tasks = session.tasks();
            return tasks == null ? 0 : tasks.size();
        } catch (RuntimeException e) {
            // a session that is stopping mid-listing is not an error worth failing on
            return 0;
        }
    }


    public NaruStmtResult executePurge(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruStmtResult rejected = rejectTargetSelectors(task, cmdLine, "purge");
        if (rejected != null) {
            return rejected;
        }
        int count = task.session().sessionStoreManager().purge();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("purged %s sessions", count));
        return NaruStmtResult.ofSuccess(count);
    }

    public NaruStmtResult executeDelete(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        TargetArgs ta = parseTargetArgs(task, cmdLine);
        if (ta.error != null) {
            return fail(task, ta.error);
        }
        if (!ta.hasSelector() && ta.positionals.isEmpty()) {
            return NaruStmtResult.ofSuccess(null);
        }

        NaruSessionStoreManager sm = task.session().sessionStoreManager();
        List<String> uuids = new ArrayList<>();
        if (ta.hasSelector()) {
            SessionTarget target;
            try {
                target = resolveTarget(task, ta);
            } catch (IllegalArgumentException e) {
                return fail(task, e.getMessage());
            }
            uuids.add(target.uuid);
        }
        for (String a : ta.positionals) {
            String uuid = sm.findByUuidOrName(a);
            if (uuid == null) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("session not found %s", a));
            } else {
                uuids.add(uuid);
            }
        }

        int count = 0;
        for (String uuid : new LinkedHashSet<>(uuids)) {
            if (sm.delete(uuid)) {
                count++;
            }
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s sessions", count));
        return NaruStmtResult.ofSuccess(count);
    }

//    public NaruStmtResult executeReload(NaruDirectiveCallContext context, NCmdLine cmdLine) {
//        NaruTask task = context.task();
//        task.session().reload();
//        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("Reloaded session."));
//        return NaruStmtResult.ofSuccess(null);
//    }

    public NaruStmtResult executeLoad(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        TargetArgs ta = parseTargetArgs(task, cmdLine);
        if (ta.error != null) {
            return fail(task, ta.error);
        }
        NaruSessionStoreManager sm = task.session().sessionStoreManager();
        String uuid;
        if (ta.hasSelector()) {
            SessionTarget target;
            try {
                target = resolveTarget(task, ta);
            } catch (IllegalArgumentException e) {
                return fail(task, e.getMessage());
            }
            uuid = target.uuid;
        } else {
            String name = ta.positionals.isEmpty() ? null : ta.positionals.get(0);
            if (NBlankable.isBlank(name)) {
                name = "main";
            }
            uuid = sm.findByUuidOrName(name);
        }
        if (uuid == null) {
            String label = ta.hasSelector() ? ta.selectorText()
                    : (ta.positionals.isEmpty() ? "main" : ta.positionals.get(0));
            NMsg msg = NMsg.ofC("session not found %s", label);
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        task.session().load(uuid);
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded session: %s", task.session().name()));
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeRestore(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        TargetArgs ta = parseTargetArgs(task, cmdLine);
        if (ta.error != null) {
            return fail(task, ta.error);
        }
        return withTarget(task, ta, target -> {
            if (target.live == null) {
                return fail(task, NMsg.ofC(
                        "session %s is not running; /session restore needs a running session",
                        target.uuid).toString());
            }
            target.live.restoreFromStore();
            context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Restored session: %s", target.live.name()));
            return NaruStmtResult.ofSuccess(null);
        });
    }

    public NaruStmtResult executeSave(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        TargetArgs ta = parseTargetArgs(task, cmdLine);
        if (ta.error != null) {
            return fail(task, ta.error);
        }
        String newName = ta.positionals.isEmpty() ? null : String.join(" ", ta.positionals).trim();
        return withTarget(task, ta, target -> {
            if (!NBlankable.isBlank(newName)) {
                if (!renameTarget(task, target, newName)) {
                    return fail(task, NMsg.ofC("session not found %s", target.uuid).toString());
                }
            }
            if (target.live == null) {
                // a session that only lives in the catalog is, by definition, already saved
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Saved session: %s",
                        NMsg.ofStyledString(target.displayName())));
                return NaruStmtResult.ofSuccess(null);
            }
            NaruSession session = target.live;
            if ((NBlankable.isBlank(session.name()) || session.name().equals("NO_NAME"))
                    && session == task.session()) {
                // only the current session can borrow the current task's conversation to guess a name
                List<NaruMessage> history = task.context(NaruSource.values()).messages();
                history.add(NaruMessage.user("can you suggest a name for this session? dont be verbose in your response, only return the suggested name please."));
                NaruModelConfig model = task.model();
                try {
                    NaruResponse chat = task.chat(model,
                            new NaruModelRequest(history,
                                    task.context(NaruSource.values()).env()
                            )
                    );
                    if (chat.getMessage() != null) {
                        session.name(chat.getMessage().getContent());
                    }
                } catch (Exception ex) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Unable ot evaluate session title using LLM : %s : %s", NMsg.ofStyledString(session.name()), ex));
                }
            }
            session.save();
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Saved session: %s", NMsg.ofStyledString(session.name())));
            return NaruStmtResult.ofSuccess(null);
        });
    }

    public NaruStmtResult executeRename(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        TargetArgs ta = parseTargetArgs(task, cmdLine);
        if (ta.error != null) {
            return fail(task, ta.error);
        }
        String newName = String.join(" ", ta.positionals).trim();
        if (NBlankable.isBlank(newName)) {
            NMsg msg = NMsg.ofC("empty or invalid session name : %s", NMsg.ofStyledString(newName));
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofSuccess(msg.toString());
        }
        return withTarget(task, ta, target -> {
            if (!renameTarget(task, target, newName)) {
                return fail(task, NMsg.ofC("session not found %s", target.uuid).toString());
            }
            NMsg msg = NMsg.ofC("Renamed session %s (%s) to %s",
                    NMsg.ofStyledString(target.displayName()), target.uuid, NMsg.ofStyledString(newName));
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofSuccess(msg.toString());
        });
    }

    public NaruStmtResult executeNew(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruStmtResult rejected = rejectTargetSelectors(task, cmdLine, "new");
        if (rejected != null) {
            return rejected;
        }
        task.session().reset(false);
        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("new session."));
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeReset(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        TargetArgs ta = parseTargetArgs(task, cmdLine);
        if (ta.error != null) {
            return fail(task, ta.error);
        }
        return withTarget(task, ta, target -> {
            if (target.live == null) {
                return fail(task, NMsg.ofC(
                        "session %s is not running; /session reset needs a running session",
                        target.uuid).toString());
            }
            target.live.reset(true);
            context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("reset session."));
            return NaruStmtResult.ofSuccess(null);
        });
    }

    public NaruStmtResult executeCopy(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        TargetArgs ta = parseTargetArgs(task, cmdLine);
        if (ta.error != null) {
            return fail(task, ta.error);
        }
        return withTarget(task, ta, target -> {
            if (target.live == null) {
                return fail(task, NMsg.ofC(
                        "session %s is not running; /session copy needs a running session",
                        target.uuid).toString());
            }
            target.live.copy();
            context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded session copy : %s", target.live.name()));
            return NaruStmtResult.ofSuccess(null);
        });
    }

    public NaruStmtResult executeCurrent(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        TargetArgs ta = parseTargetArgs(task, cmdLine);
        if (ta.error != null) {
            return fail(task, ta.error);
        }
        return withTarget(task, ta, target -> {
            NMsg msg = ta.hasSelector()
                    ? NMsg.ofC("Session: %s (%s)", target.displayName(), target.uuid)
                    : NMsg.ofC("Current session: %s (%s)", target.displayName(), target.uuid);
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            NStringBuilder sb = NStringBuilder.of();
            sb.println(msg.toString());
            return NaruStmtResult.ofSuccess(sb.toString());
        });
    }

    public NaruStmtResult executeChangeVisibility(NaruVisibility makePublic, NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        TargetArgs ta = parseTargetArgs(task, cmdLine);
        if (ta.error != null) {
            return fail(task, ta.error);
        }
        return withTarget(task, ta, target -> {
            if (target.live != null) {
                if (target.live.getVisibility() != makePublic) {
                    target.live.setVisibility(makePublic);
                    target.live.save();
                }
            } else {
                if (!task.session().sessionStoreManager().setVisibility(target.uuid, makePublic)) {
                    return fail(task, NMsg.ofC("session not found %s", target.uuid).toString());
                }
            }
            NMsg msg = NMsg.ofC("make session %s %s: %s (%s)",
                    target.displayName(),
                    makePublic == NaruVisibility.PUBLIC ? "public" : "private",
                    target.uuid,
                    target.live != null ? "running" : "saved");
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofSuccess(null);
        });
    }

}
