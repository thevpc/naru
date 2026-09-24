package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.nuts.cmdline.NCmdLine;

public class NaruGoDirective extends NaruDirectiveBase {
    public NaruGoDirective() {
        super("go", "general", "call model without additional prompt");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String prompt = task.inputBuffer();
                task.inputBuffer("");
                task.prependStatement(NaruStatementHelper.ofModelCall(prompt));
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }

}
