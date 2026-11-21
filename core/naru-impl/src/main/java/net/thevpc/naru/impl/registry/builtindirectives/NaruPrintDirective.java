package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;

public class NaruPrintDirective extends NaruDirectiveBase {
    public NaruPrintDirective() {
        super("print", "general", "print and append to context", "write");
        register(new AbstractSubCommand(new SubCommandHelp("<expression>", "print and append to context\nex:\n/print \"$message\"\nevaluates $message and print the result")) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NElement e = context.task().evalExpression(context.argument());
                // a string prints as its text: the element's own form quotes it, so
                // /print "done" used to print "done" with the quotes
                String line = e == null ? "" : e.isAnyString() ? e.asStringValue().orElse("") : e.toString();
                task.addResultMessage(NMsg.ofC("%s", line));
                return NaruStmtResult.ofSuccess(
                        e == null ? NElement.ofNull() : e,
                        NMsg.ofC("%s", line)
                );
            }
        });
    }



}
