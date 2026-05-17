package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.text.NMsg;

public class PwdDirective extends AbstractDirective {
    public PwdDirective() {
        super("pwd", "print working directory");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        context.session().addHistory(NaruMessage.user(NMsg.ofC("current working directory is %s", sessionContext.workingDir()).toString()));
        context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", sessionContext.workingDir()));
    }
}
