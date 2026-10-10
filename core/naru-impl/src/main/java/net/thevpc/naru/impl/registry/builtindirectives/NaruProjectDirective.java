package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

/**
 * {@code /project <dir>} -- the explicit project switch (WP7).
 * <p>
 * Unlike {@code /cd}, which only moves the working directory, this changes the project
 * root. That makes it the one directive entitled to side effects: it re-resolves what is
 * rooted at the project (skill roots, context files, model defaults), runs the
 * workspace-level {@code init.naru} exactly once, and fires the {@code project-change}
 * event. Per-task selections and granted tag sets are kept; only availability is
 * re-resolved (an existing task's selected model is fixed at task creation, so "models"
 * here means the model <em>defaults</em> the new root provides), so a loaded skill that
 * no longer exists at the new root is reported by {@code /skill doctor} rather than
 * silently unloaded.
 */
public class NaruProjectDirective extends NaruDirectiveBase {

    public NaruProjectDirective() {
        super("project", "session", "change the project directory");
        register(new AbstractSubCommand(new SubCommandHelp("<dir>",
                "change the project root to <dir>, re-resolve roots/context files/model defaults, run the "
                        + "workspace init.naru once and fire project-change.\n"
                        + "ex:\n/project /path/to/other/project")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruSession session = task.session();
                String arg = context.argument();
                if (NBlankable.isBlank(arg)) {
                    NMsg msg = NMsg.ofC("project: missing <dir> (current project is %s)",
                            session.projectDir()).asError();
                    task.addResultMessage(msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NPath target = NPath.of(arg.trim()).toAbsolute(session.projectDir()).normalize();
                if (!target.isDirectory()) {
                    NMsg msg = NMsg.ofC("project directory not found: %s", target).asError();
                    task.addResultMessage(msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NPath old = session.projectDir();
                session.projectDir(target);
                // exactly once per /project: the workspace init, read from the new root
                task.runInitHooks(NaruEvent.PROJECT_CHANGE);
                NPath now = session.projectDir();
                if (now.equals(old)) {
                    task.addResultMessage(NMsg.ofC("project is already %s", now));
                } else {
                    task.addResultMessage(NMsg.ofC("project changed to %s", now));
                }
                return NaruStmtResult.ofSuccess(now.toString());
            }
        });
    }
}
