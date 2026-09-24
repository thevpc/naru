package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.agent.NAruInputMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.nuts.cmdline.NCmdLine;

public class NaruBufferDirective extends NaruDirectiveBase {
    public NaruBufferDirective() {
        super("buffer", "general", "switch input mode (line <> buffer)");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String arg = context.argument() == null ? "" : context.argument().trim();
                NAruInputMode m;
                switch (arg) {
                    case "on":
                        m = NAruInputMode.BLOC;
                        break;
                    case "off":
                        m = NAruInputMode.LINE;
                        break;
                    default:
                        // bare "/buffer" toggles
                        m = task.inputMode() == NAruInputMode.LINE ? NAruInputMode.BLOC : NAruInputMode.LINE;
                        break;
                }
                task.inputMode(m);
                return NaruStmtResult.ofSuccess(m);
            }
        });
    }

}
