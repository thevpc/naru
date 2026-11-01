package net.thevpc.naru.ext.tools.projects;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.project.NaruProject;
import net.thevpc.naru.api.project.NaruProjectDeletePolicy;
import net.thevpc.naru.api.project.NaruProjectNode;
import net.thevpc.naru.api.project.NaruProjectPatch;
import net.thevpc.naru.api.project.NaruProjectQuery;
import net.thevpc.naru.api.project.NaruProjectResult;
import net.thevpc.naru.api.project.NaruProjectService;
import net.thevpc.naru.api.project.NaruRemoveReport;
import net.thevpc.naru.api.project.NaruMoveReport;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.store.NaruPage;
import net.thevpc.naru.api.store.NaruSessionQuery;
import net.thevpc.naru.api.store.NaruProjectFilter;
import net.thevpc.naru.api.store.NaruSessionSummary;
import net.thevpc.naru.api.store.NaruUserFilter;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /project}: manage the project entity — grouping labels that supply initial
 * values (folder, user) to new sessions and organize the session tree.
 *
 * <p>Semantic, not restrictive: editing a project never re-touches existing sessions, and
 * {@code /project move} re-points sessions by changing {@code projectId} only — never
 * directories, never storage locations. This directive is user-side only; no model-facing
 * tool reads or writes projects.
 *
 * <p>It is deliberately <b>not</b> {@code /sessiondir}: that directive changes the current
 * session's anchor directory, this one manages the grouping entities.
 */
public class NaruProjectDirective extends NaruDirectiveBase {

    public NaruProjectDirective() {
        super("project", "project", "manage projects: grouping labels that seed new sessions", "projects");
        noCommand("list");
        register(new AbstractSubCommand("list", NText.ofPlain("list projects")
                , new SubCommandHelp("[--user=<u>|--no-user] [--filter=<text>]",
                "list projects with id, name, folder, user and session count")
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("show", NText.ofPlain("show one project")
                , new SubCommandHelp("<id|name>", "show one project, including its version token")
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeShow(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("add", NText.ofPlain("create a project")
                , new SubCommandHelp("<name> --folder=<path> [--user=<u>]",
                "create a project; the folder is required and may not exist yet (it warns)")
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeAdd(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("edit", NText.ofPlain("edit a project")
                , new SubCommandHelp("<id|name> [--name=<n>] [--folder=<path>] [--user=<u>|--no-user]",
                "edit a project; existing sessions are never changed by this")
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeEdit(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("remove", NText.ofPlain("remove a project")
                , new SubCommandHelp("<id|name> [--sessions=reject|detach|cascade]",
                "remove a project; sessions=reject (default) refuses while any session "
                        + "points at it, detach clears their projectId, cascade deletes "
                        + "the sessions first and refuses if any is live")
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeRemove(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("move", NText.ofPlain("move sessions between projects")
                , new SubCommandHelp("[--to=<id|name|none>] <sessionId|name>...",
                "re-point sessions at a project (default: none, standalone); changes only projectId")
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeMove(context, cmdLine);
            }
        });
    }

    // ---------------------------------------------------------------- services

    /** The option value of an argument, or null when absent. */
    private static String opt(net.thevpc.nuts.cmdline.NArg arg) {
        return arg.stringValue();
    }

    private static void add(List<String> target, String value) {
        if (value != null) {
            target.add(value);
        }
    }

    private static NaruProjectService service(SubCommandContext context) {
        NaruSession session = context.task().session();
        NaruWorkspace workspace = session.agent();
        return workspace.projects();
    }

    private static NaruStmtResult fail(SubCommandContext context, String message) {
        NMsg msg = NMsg.ofC("%s", message).asError();
        context.errln(msg);
        return NaruStmtResult.ofError(msg);
    }

    // ---------------------------------------------------------------- list

    private NaruStmtResult executeList(SubCommandContext context, NCmdLine cmdLine) {
        final NaruUserFilter[] user = {NaruUserFilter.any()};
        final String[] filter = {null};
        try {
            cmdLine.matcher()
                    .when("--user").asEntry(a -> {
                        String v = opt(a);
                        if (v != null) {
                            user[0] = NaruUserFilter.ofName(v);
                        }
                    })
                    .when("--no-user").asFlag(a -> {
                        if (a.booleanValue()) {
                            user[0] = NaruUserFilter.none();
                        }
                    })
                    .when("--filter").asEntry(a -> filter[0] = opt(a))
                    .requireAll();
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
        NaruProjectService service = service(context);
        NaruProjectQuery query = new NaruProjectQuery(null, null, user[0], filter[0],
                NaruProjectQuery.DEFAULT_LIMIT);
        int shown = 0;
        while (true) {
            NaruPage<NaruProjectNode> page;
            try {
                page = service.tree(query);
            } catch (Exception e) {
                return fail(context, String.valueOf(e.getMessage()));
            }
            for (NaruProjectNode node : page.items()) {
                printProjectRow(context, node.project(), node.sessionCount());
                shown++;
            }
            if (page.nextCursor() == null) {
                break;
            }
            query = query.after(page.nextCursor());
        }
        context.outln(NMsg.ofC("%s %s",
                NMsg.ofStyledPale("no project:"),
                NMsg.ofC("%s session(s)",
                        NMsg.ofStyledNumber(String.valueOf(safeStandaloneCount(service))))));
        if (shown == 0) {
            context.outln(NMsg.ofC("no projects. create one with %s",
                    NMsg.ofStyledPrimary1("/project add <name> --folder=<path>")));
        }
        return NaruStmtResult.ofSuccess(NElement.of(shown));
    }

    private long safeStandaloneCount(NaruProjectService service) {
        try {
            return service.countStandaloneSessions();
        } catch (Exception e) {
            return 0;
        }
    }

    private void printProjectRow(SubCommandContext context, NaruProject p, long sessionCount) {
        context.outln(NMsg.ofC("%s %s folder=%s user=%s sessions=%s",
                NMsg.ofStyledPrimary3(p.id()),
                NMsg.ofStyledString(p.name()),
                NMsg.ofStyledPath(String.valueOf(p.folder())),
                NMsg.ofStyledKeyword(p.user() == null ? "-" : p.user()),
                NMsg.ofStyledNumber(String.valueOf(sessionCount))));
    }

    // ---------------------------------------------------------------- show

    private NaruStmtResult executeShow(SubCommandContext context, NCmdLine cmdLine) {
        final List<String> positionals = new ArrayList<>();
        try {
            cmdLine.matcher().whenNonOption().asArg(a -> add(positionals, a.image()))
                    .requireAll();
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
        if (positionals.size() != 1) {
            return fail(context, "usage: /project show <id|name>");
        }
        NaruProjectService service = service(context);
        NOptional<NaruProject> found;
        try {
            found = service.find(positionals.get(0));
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
        if (found.isEmpty()) {
            return fail(context, "project not found: " + positionals.get(0));
        }
        NaruProject p = found.get();
        long count;
        try {
            // ask through the real counts path: sessions() pages, countByProject passes scan headers
            count = countSessions(service, p.id());
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
        context.outln(NMsg.ofC("%s %s", NMsg.ofStyledPale("id:"), NMsg.ofStyledPrimary3(p.id())));
        context.outln(NMsg.ofC("%s %s", NMsg.ofStyledPale("name:"), NMsg.ofStyledString(p.name())));
        context.outln(NMsg.ofC("%s %s", NMsg.ofStyledPale("folder:"), NMsg.ofStyledPath(String.valueOf(p.folder()))));
        context.outln(NMsg.ofC("%s %s", NMsg.ofStyledPale("user:"),
                NMsg.ofStyledKeyword(p.user() == null ? "-" : p.user())));
        context.outln(NMsg.ofC("%s %s", NMsg.ofStyledPale("created:"),
                NMsg.ofStyledNumber(String.valueOf(p.createdAt()))));
        context.outln(NMsg.ofC("%s %s", NMsg.ofStyledPale("updated:"),
                NMsg.ofStyledNumber(String.valueOf(p.updatedAt()))));
        context.outln(NMsg.ofC("%s %s", NMsg.ofStyledPale("version:"),
                NMsg.ofStyledNumber(String.valueOf(p.version()))));
        context.outln(NMsg.ofC("%s %s", NMsg.ofStyledPale("sessions:"),
                NMsg.ofStyledNumber(String.valueOf(count))));
        if (!p.folder().isDirectory()) {
            context.outln(NMsg.ofC("%s folder does not exist: %s",
                    NMsg.ofStyledError("warning:"), NMsg.ofStyledPath(String.valueOf(p.folder()))));
        }
        return NaruStmtResult.ofSuccess(p.toElement());
    }

    private long countSessions(NaruProjectService service, String projectId) {
        long count = 0;
        String cursor = null;
        while (true) {
            NaruPage<NaruSessionSummary> page = service.sessions(new NaruSessionQuery(
                    cursor, null, NaruProjectFilter.ofId(projectId), NaruUserFilter.any(),
                    NaruSessionQuery.MAX_LIMIT));
            count += page.items().size();
            if (page.nextCursor() == null) {
                return count;
            }
            cursor = page.nextCursor();
        }
    }

    // ---------------------------------------------------------------- add

    private NaruStmtResult executeAdd(SubCommandContext context, NCmdLine cmdLine) {
        final List<String> positionals = new ArrayList<>();
        final NPath[] folder = {null};
        final String[] user = {null};
        try {
            cmdLine.matcher()
                    .when("--folder").asEntry(a -> {
                        String v = opt(a);
                        if (v != null) {
                            folder[0] = NPath.of(v);
                        }
                    })
                    .when("--user").asEntry(a -> user[0] = opt(a))
                    .whenNonOption().asArg(a -> add(positionals, a.image()))
                    .requireAll();
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
        String name = String.join(" ", positionals).trim();
        if (NBlankable.isBlank(name)) {
            return fail(context, "usage: /project add <name> --folder=<path> [--user=<u>]");
        }
        if (folder[0] == null) {
            return fail(context, "folder required: /project add <name> --folder=<path>");
        }
        NaruProject created;
        try {
            NaruProjectResult<NaruProject> result =
                    service(context).add(name, folder[0], user[0]);
            created = result.value();
            for (String warning : result.warnings()) {
                context.outln(NMsg.ofC("%s %s", NMsg.ofStyledError("warning:"), warning));
            }
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
        printProjectRow(context, created, 0);
        return NaruStmtResult.ofSuccess(created.toElement());
    }

    // ---------------------------------------------------------------- edit

    private NaruStmtResult executeEdit(SubCommandContext context, NCmdLine cmdLine) {
        final List<String> positionals = new ArrayList<>();
        final NaruProjectPatch patch = NaruProjectPatch.of();
        final boolean[] clearUser = {false};
        try {
            cmdLine.matcher()
                    .when("--name").asEntry(a -> {
                        String v = opt(a);
                        if (v != null) {
                            patch.name(v);
                        }
                    })
                    .when("--folder").asEntry(a -> {
                        String v = opt(a);
                        if (v != null) {
                            patch.folder(NPath.of(v));
                        }
                    })
                    .when("--user").asEntry(a -> {
                        String v = opt(a);
                        if (v != null) {
                            patch.user(v);
                        }
                    })
                    .when("--no-user").asFlag(a -> {
                        if (a.booleanValue()) {
                            clearUser[0] = true;
                        }
                    })
                    .whenNonOption().asArg(a -> add(positionals, a.image()))
                    .requireAll();
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
        if (positionals.size() != 1) {
            return fail(context, "usage: /project edit <id|name> [--name=<n>] [--folder=<path>] [--user=<u>|--no-user]");
        }
        if (patch.isEmpty() && !clearUser[0]) {
            return fail(context, "nothing to change: pass at least one of --name/--folder/--user/--no-user");
        }
        if (clearUser[0]) {
            patch.clearUser();
        }
        NaruProjectService service = service(context);
        try {
            NaruProject current = service.find(positionals.get(0)).orElseThrow(
                    () -> new IllegalArgumentException("project not found: " + positionals.get(0)));
            NaruProjectResult<NaruProject> result = service.edit(current.id(), current.version(), patch);
            for (String warning : result.warnings()) {
                context.outln(NMsg.ofC("%s %s", NMsg.ofStyledError("warning:"), warning));
            }
            printProjectRow(context, result.value(), countSessions(service, result.value().id()));
            return NaruStmtResult.ofSuccess(result.value().toElement());
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- remove

    private NaruStmtResult executeRemove(SubCommandContext context, NCmdLine cmdLine) {
        final List<String> positionals = new ArrayList<>();
        final String[] sessionsValue = {null};
        try {
            cmdLine.matcher()
                    .when("--sessions").asEntry(a -> sessionsValue[0] = opt(a))
                    .whenNonOption().asArg(a -> add(positionals, a.image()))
                    .requireAll();
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
        if (positionals.size() != 1) {
            return fail(context, "usage: /project remove <id|name> [--sessions=reject|detach|cascade]");
        }
        NaruProjectDeletePolicy policy;
        if (sessionsValue[0] == null || sessionsValue[0].isBlank()) {
            policy = NaruProjectDeletePolicy.REJECT;
        } else {
            try {
                policy = NaruProjectDeletePolicy.valueOf(sessionsValue[0].trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fail(context, "unknown --sessions value '" + sessionsValue[0]
                        + "': use reject, detach or cascade");
            }
        }
        NaruProjectService service = service(context);
        try {
            NaruProject current = service.find(positionals.get(0)).orElseThrow(
                    () -> new IllegalArgumentException("project not found: " + positionals.get(0)));
            if (policy == NaruProjectDeletePolicy.CASCADE) {
                context.outln(NMsg.ofC("cascade will delete every session of %s and then the project",
                        NMsg.ofStyledString(current.name())));
            }
            NaruRemoveReport report = service.remove(current.id(), current.version(), policy);
            context.outln(NMsg.ofC("%s project %s%s",
                    report.projectDeleted() ? "removed" : "NOT removed:",
                    NMsg.ofStyledString(current.name()),
                    report.projectDeleted() ? "" : " (failures below)"));
            if (!report.sessionsDetached().isEmpty()) {
                context.outln(NMsg.ofC("%s detached: %s", NMsg.ofStyledKeyword("sessions"),
                        report.sessionsDetached().size()));
            }
            if (!report.sessionsDeleted().isEmpty()) {
                context.outln(NMsg.ofC("%s deleted: %s", NMsg.ofStyledKeyword("sessions"),
                        report.sessionsDeleted().size()));
            }
            report.failedSessions().forEach((id, reason) ->
                    context.outln(NMsg.ofC("%s %s: %s", NMsg.ofStyledError("failed:"), id, reason)));
            return report.projectDeleted()
                    ? NaruStmtResult.ofSuccess(NElement.of(true))
                    : NaruStmtResult.ofError(NMsg.ofC("partial removal: %s session(s) failed",
                    report.failedSessions().size()));
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- move

    private NaruStmtResult executeMove(SubCommandContext context, NCmdLine cmdLine) {
        final List<String> positionals = new ArrayList<>();
        final String[] to = {null};
        try {
            cmdLine.matcher()
                    .when("--to").asEntry(a -> to[0] = opt(a))
                    .whenNonOption().asArg(a -> add(positionals, a.image()))
                    .requireAll();
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
        if (positionals.isEmpty()) {
            return fail(context, "usage: /project move [--to=<id|name|none>] <sessionId|name>...");
        }
        try {
            NaruMoveReport report = service(context).moveSessions(positionals,
                    to[0] == null ? NaruProjectService.NO_PROJECT : to[0]);
            report.moved().forEach((id, target) ->
                    context.outln(NMsg.ofC("moved %s -> %s", id,
                            target == null ? NMsg.ofStyledPale("(no project)") : target)));
            for (String id : report.unchanged()) {
                context.outln(NMsg.ofC("%s %s already in that project",
                        NMsg.ofStyledPale("unchanged:"), id));
            }
            report.failed().forEach((id, reason) ->
                    context.outln(NMsg.ofC("%s %s: %s", NMsg.ofStyledError("failed:"), id, reason)));
            return report.isSuccess()
                    ? NaruStmtResult.ofSuccess(NElement.of(report.moved().size() + report.unchanged().size()))
                    : NaruStmtResult.ofError(NMsg.ofC("%s session(s) failed to move", report.failed().size()));
        } catch (Exception e) {
            return fail(context, String.valueOf(e.getMessage()));
        }
    }
}
