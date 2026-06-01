package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.stmt.NaruStatementHelper;

public class NaruGoDirective extends AbstractDirective {
    public NaruGoDirective() {
        super("go", "general", "call model without additional prompt");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        String prompt = task.inputBuffer();
        task.inputBuffer("");
        task.addStatement(NaruStatementHelper.ofModelCall(prompt));
        task.tick();
    }
}
