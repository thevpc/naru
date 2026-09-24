package net.thevpc.naru.ext.tools.sessions;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

public class NaruRestoreDirective extends NaruDirectiveBase {
    public NaruRestoreDirective() {
        super("restore","session", "resume from last snapshot");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                task.session().restoreSnapshot();
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Restored session: %s", task.session().name()));
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }

}