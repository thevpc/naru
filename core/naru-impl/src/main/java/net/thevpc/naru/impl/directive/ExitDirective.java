package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NCancelException;

public class ExitDirective extends AbstractDirective {
    public ExitDirective() {
        super("exit","general", "exit the agent");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        context.session().log(NaruLogMode.TRACE,NMsg.ofC("Exiting agent."));
        throw new NCancelException();
    }
}
