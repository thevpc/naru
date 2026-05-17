package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.text.NMsg;

import java.util.logging.Level;

public class ShDirective extends AbstractDirective {
    public ShDirective() {
        super("sh", "run shell command");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        try (NSession session = NSession.of().copy()) {
            session.setLogTermLevel(Level.OFF);
            session.runWith(() -> {
                NExec e = NExec.of("nsh", "-c", context.argument()).directory(context.session().workingDir()).failFast(false);
                String result = e
                        .getGrabbedAllString();
                context.session().addHistory(NaruMessage.user(NMsg.ofC("call   : nsh -c %s", context.argument()).toString()));
                context.session().addHistory(NaruMessage.user(NMsg.ofC("exit code %s", e.exitCode()).toString()));
                context.session().addHistory(NaruMessage.user(NMsg.ofC("result : \n%s", NaruUtils.stripAnsi(result)).toString()));
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
            });
        }
    }
}
