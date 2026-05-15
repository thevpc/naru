package net.thevpc.naru.impl.registry.directives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSessionContext;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.text.NMsg;

public class ExitDirective extends AbstractDirective {
    public ExitDirective() {
        super("exit", "exit the agent");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSessionContext sessionContext = context.session();
        context.session().runner().log(NaruLogMode.TRACE,NMsg.ofC("Exiting agent."));
        sessionContext.setForever(false);
        sessionContext.terminate();
    }
}
