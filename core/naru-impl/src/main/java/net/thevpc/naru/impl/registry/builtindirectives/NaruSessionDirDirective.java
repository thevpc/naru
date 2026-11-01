package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

/**
 * {@code /sessiondir <dir>} -- the explicit session-directory switch (WP7).
 * <p>
 * Unlike {@code /cd}, which only moves the working directory, this changes the session directory
 * root. That makes it the one directive entitled to side effects: it re-resolves what is
 * rooted at the session directory (skill roots, context files, model defaults), runs the
 * workspace-level {@code init.naru} exactly once, and fires the {@code sessiondir-change}
 * event. Per-task selections and granted tag sets are kept; only availability is
 * re-resolved (an existing task's selected model is fixed at task creation, so "models"
 * here means the model <em>defaults</em> the new root provides), so a loaded skill that
 * no longer exists at the new root is reported by {@code /skill doctor} rather than
 * silently unloaded.
 */
public class NaruSessionDirDirective extends NaruDirectiveBase {

    public NaruSessionDirDirective() {
        super("sessiondir", "session", "change the session directory");
        register(new AbstractSubCommand(new SubCommandHelp("<dir>",
                "change the session directory to <dir>, re-resolve roots/context files/model defaults, run the "
                        + "workspace init.naru once and fire sessiondir-change.\n"
                        + "ex:\n/sessiondir /path/to/other/sessiondir")) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruSession session = task.session();
                String arg = context.argument();
                if (NBlankable.isBlank(arg)) {
                    NMsg msg = NMsg.ofC("sessiondir: missing <dir> (current session directory is %s)",
                            session.sessionDir()).asError();
                    task.addResultMessage(msg);
                    return NaruStmtResult.ofError(msg);
                }
                NPath target = NPath.of(arg.trim()).toAbsolute(session.sessionDir()).normalize();
                if (!target.isDirectory()) {
                    NMsg msg = NMsg.ofC("session directory not found: %s", target).asError();
                    task.addResultMessage(msg);
                    return NaruStmtResult.ofError(msg);
                }
                NPath old = session.sessionDir();
                session.sessionDir(target);
                // exactly once per /sessiondir: the workspace init, read from the new root
                task.runInitHooks(NaruEvent.SESSION_DIR_CHANGE);
                NPath now = session.sessionDir();
                if (now.equals(old)) {
                    task.addResultMessage(NMsg.ofC("session directory is already %s", now));
                } else {
                    task.addResultMessage(NMsg.ofC("session directory changed to %s", now));
                }
                return NaruStmtResult.ofSuccess(NElement.of(now.toString()), NMsg.ofC("%s", now));
            }
        });
    }
}
