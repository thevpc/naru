package net.thevpc.naru.impl.registry.builtindirectives.general;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NCancelException;

public class NaruExitDirective extends AbstractDirective {
    public NaruExitDirective() {
        super("exit","general", "exit the agent");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        context.task().log(NaruLogMode.TRACE,NMsg.ofC("Exiting agent."));
        throw new NCancelException();
    }
}
