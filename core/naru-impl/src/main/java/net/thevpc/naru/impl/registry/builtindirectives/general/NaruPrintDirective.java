package net.thevpc.naru.impl.registry.builtindirectives.general;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;

public class NaruPrintDirective extends AbstractDirective {
    public NaruPrintDirective() {
        super("print", "general", "print and append to context", "write");
        register(new AbstractSubCommand(new SubCommandHelp("<expression>", "print and append to context\nex:\n/print \"$message\"\nevaluates $message and print the result")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                Object e = context.task().evalExpression(context.argument());
                String line = String.valueOf(e);
                task.addResultMessage(NMsg.ofC("%s", line));
            }
        });
    }



}
