package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NAruInputMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.stmt.NaruStatementHelper;

public class BufferDirective extends AbstractDirective {
    public BufferDirective() {
        super("buffer", "general", "switch input mode (line <> buffer)");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession session = context.session();
        session.inputMode(session.inputMode() == NAruInputMode.LINE ? NAruInputMode.BLOC : NAruInputMode.LINE);
    }
}
