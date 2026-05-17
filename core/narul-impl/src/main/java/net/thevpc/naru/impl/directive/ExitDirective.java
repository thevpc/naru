package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.text.NMsg;

public class ExitDirective extends AbstractDirective {
    public ExitDirective() {
        super("exit", "exit the agent");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        context.session().log(NaruLogMode.TRACE,NMsg.ofC("Exiting agent."));
        sessionContext.setForever(false);
        sessionContext.terminate();
    }
}
