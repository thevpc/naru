package net.thevpc.naru.impl.registry.builtindirectives.session;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionManager;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;

public class NaruRestoreDirective extends AbstractDirective {
    public NaruRestoreDirective() {
        super("restore","session", "resume from last snapshot");
        register(new AbstractSubCommand() {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruSessionManager sm = task.session().sessionManager();
                sm.restoreSnapshot();
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Restored session: %s", task.session().name()));
            }
        });
    }

}
