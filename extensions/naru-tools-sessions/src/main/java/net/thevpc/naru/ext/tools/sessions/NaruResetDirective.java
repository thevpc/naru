package net.thevpc.naru.ext.tools.sessions;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

public class NaruResetDirective extends NaruDirectiveBase {
    public NaruResetDirective() {
        super("reset","session", "reset current session");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                task.session().reset(true);
                context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("reset session."));
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }

}