package net.thevpc.naru.ext.tools.sessions;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

public class NaruNewDirective extends NaruDirectiveBase {
    public NaruNewDirective() {
        super("new","session", "start a new session.");
        register(new AbstractSubCommand(new SubCommandHelp(
                "","start a new session\ncurrent session will terminate but not saved."
        )) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeNew(context, cmdLine);
            }
        });
    }

    public NaruStmtResult executeNew(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().terminate();
        task.session().reset(false);
        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("reset session."));
        return NaruStmtResult.ofSuccess(null);
    }

}