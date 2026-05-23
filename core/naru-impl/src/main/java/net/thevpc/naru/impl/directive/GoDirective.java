package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.stmt.NaruStatementHelper;

public class GoDirective extends AbstractDirective {
    public GoDirective() {
        super("go","general", "call model without additional prompt");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        sessionContext.pushStatement(NaruStatementHelper.ofModelCall(null));
        sessionContext.runStep();
    }
}
