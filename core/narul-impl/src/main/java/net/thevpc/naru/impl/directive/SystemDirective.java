package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.platform.NOsFamily;
import net.thevpc.nuts.text.NMsg;

import java.util.logging.Level;

public class SystemDirective extends AbstractDirective {
    public SystemDirective() {
        super("system", "run system command");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession naruSession = context.session();
        try (NSession session = NSession.of().copy()) {
            session.setLogTermLevel(Level.OFF);
            session.runWith(() -> {
                NExec e = null;
                if (NEnv.of().osFamily() == NOsFamily.WINDOWS) {
                    e = NExec.of("cmd", "/c", context.argument());
                } else {
                    e = NExec.of("sh", "-c", context.argument());
                }
                e.directory(naruSession.workingDir()).failFast(false);
                String result = e
                        .getGrabbedAllString();
                naruSession.addHistory(NaruMessage.user(NMsg.ofC("call   : system %s", context.argument()).toString()));
                naruSession.addHistory(NaruMessage.user(NMsg.ofC("exit code %s", e.exitCode()).toString()));
                naruSession.addHistory(NaruMessage.user(NMsg.ofC("result : \n%s", NaruUtils.stripAnsi(result)).toString()));
                naruSession.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                naruSession.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
            });
        }
    }
}
