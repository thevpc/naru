package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

public class NaruPrintDirective extends NaruDirectiveBase {
    public NaruPrintDirective() {
        super("print", "general", "print and append to context", "write");
        register(new AbstractSubCommand(new SubCommandHelp("<expression>", "print and append to context\nex:\n/print \"$message\"\nevaluates $message and print the result")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                Object e = context.task().evalExpression(context.argument());
                String line = String.valueOf(e);
                task.addResultMessage(NMsg.ofC("%s", line));
                return NaruStmtResult.ofSuccess(e);
            }
        });
    }



}
