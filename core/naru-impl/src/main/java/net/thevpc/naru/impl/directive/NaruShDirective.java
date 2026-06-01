package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.text.NMsg;

import java.util.logging.Level;

public class NaruShDirective extends AbstractDirective {
    public NaruShDirective() {
        super("sh","general", "run shell command");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        try (NSession session = NSession.of().copy()) {
            session.setLogTermLevel(Level.OFF);
            session.runWith(() -> {
                NExec e = NExec.of("nsh","--progress=none", "-c", context.argument()).directory(task.workingDir()).failFast(false);
                String result = e
                        .grabbedAll();
                task.addHistory(NaruMessage.user(NMsg.ofC("call   : nsh -c %s", context.argument()).toString()));
                task.addHistory(NaruMessage.user(NMsg.ofC("exit code %s", e.exitCode()).toString()));
                task.addHistory(NaruMessage.user(NMsg.ofC("result : \n%s", NaruUtils.stripAnsi(result)).toString()));
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
            });
        }
    }
}
