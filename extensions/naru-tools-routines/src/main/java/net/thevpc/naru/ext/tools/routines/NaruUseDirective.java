package net.thevpc.naru.ext.tools.routines;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

public class NaruUseDirective extends NaruDirectiveBase {
    public NaruUseDirective() {
        super("use", "routine", "use a routine");
        register(new AbstractSubCommand(
                new SubCommandHelp("<routine>", "use routine by name")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NArg n = cmdLine.next().orNull();
                if(n==null || n.isOption()){
                    NMsg msg = NMsg.ofC("expected <routine-name>");
                    context.task().throwError(msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                context.task().frame().editRoutine(n.image());
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }

}
