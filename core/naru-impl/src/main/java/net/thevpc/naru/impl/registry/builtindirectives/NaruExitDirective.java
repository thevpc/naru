package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NCancelException;

public class NaruExitDirective extends NaruDirectiveBase {
    public NaruExitDirective() {
        super("exit","general", "exit the agent");
        register(new AbstractSubCommand("", NText.ofPlain("exit agent")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                context.task().log(NaruLogMode.TRACE,NMsg.ofC("Exiting Naru."));
                throw new NCancelException();
            }
        });
    }

}
