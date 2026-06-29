package net.thevpc.naru.impl.registry.builtindirectives.general;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.nuts.cmdline.NCmdLine;

public class NaruGoDirective extends AbstractDirective {
    public NaruGoDirective() {
        super("go", "general", "call model without additional prompt");
        register(new AbstractSubCommand() {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String prompt = task.inputBuffer();
                task.inputBuffer("");
                task.prependStatement(NaruStatementHelper.ofModelCall(prompt));
                //task.tick();
            }
        });
    }

}
