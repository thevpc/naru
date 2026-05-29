package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.stmt.NaruStatementHelper;

public class GoDirective extends AbstractDirective {
    public GoDirective() {
        super("go", "general", "call model without additional prompt");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession session = context.session();
        String prompt = session.inputBuffer();
        session.inputBuffer("");
        session.addStatement(NaruStatementHelper.ofModelCall(prompt));
        session.runStep();
    }
}
