package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

public class NaruPwdDirective extends NaruDirectiveBase {
    public NaruPwdDirective() {
        super("pwd","fs", "print working directory");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                context.task().addHistory(NaruMessage.user(NMsg.ofC("current working directory is %s", task.workingDir()).toString()));
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", task.workingDir()));
                return NaruStmtResult.ofSuccess(task.workingDir().toString());
            }
        });
    }

}
