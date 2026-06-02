package net.thevpc.naru.impl.registry.builtindirectives.general;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
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
