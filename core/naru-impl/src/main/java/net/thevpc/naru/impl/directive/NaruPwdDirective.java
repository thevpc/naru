package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.text.NMsg;

public class NaruPwdDirective extends AbstractDirective {
    public NaruPwdDirective() {
        super("pwd","general", "print working directory");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        context.task().addHistory(NaruMessage.user(NMsg.ofC("current working directory is %s", task.workingDir()).toString()));
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", task.workingDir()));
    }
}
