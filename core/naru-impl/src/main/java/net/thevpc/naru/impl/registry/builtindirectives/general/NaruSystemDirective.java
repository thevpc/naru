package net.thevpc.naru.impl.registry.builtindirectives.general;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.platform.NOsFamily;
import net.thevpc.nuts.text.NMsg;

import java.util.logging.Level;

public class NaruSystemDirective extends AbstractDirective {
    public NaruSystemDirective() {
        super("system","general", "run system command","sys");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        boolean grab=false;
        try (NSession session = NSession.of().copy()) {
            session.setLogTermLevel(Level.OFF);
            session.runWith(() -> {
                NExec e = null;
                if (NEnv.of().osFamily() == NOsFamily.WINDOWS) {
                    e = NExec.of("cmd", "/c", context.argument());
                } else {
                    e = NExec.of("sh", "-c", context.argument());
                }
                e.directory(task.workingDir()).failFast(false);
                String result =null;
                if(grab) {
                    result = e
                            .grabbedAll();
                }else {
                    e.run();
                }
                task.addHistory(NaruMessage.user(NMsg.ofC("call   : system %s", context.argument()).toString()));
                task.addHistory(NaruMessage.user(NMsg.ofC("exit code %s", e.exitCode()).toString()));
                if(grab) {
                    task.addHistory(NaruMessage.user(NMsg.ofC("result : \n%s", NaruUtils.stripAnsi(result)).toString()));
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                }
            });
        }
    }
}
