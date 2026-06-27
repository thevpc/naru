package net.thevpc.naru.impl.registry.builtindirectives.general;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.text.NMsg;

import java.util.logging.Level;

public class NaruShDirective extends AbstractDirective {
    public NaruShDirective() {
        super("sh","general", "run shell command");
        register(new AbstractSubCommand() {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                try (NSession session = NSession.of().copy()) {
                    session.setLogTermLevel(Level.OFF);
                    session.runWith(() -> {
                        NExec e = NExec.of("nsh","--progress=none", "-c", context.argument()).directory(task.workingDir()).failFast(false);
                        String result = e
                                .grabbedAll();
                        task.addResultMessage(
                                NMsg.ofC("call   : nsh -c %s\nexit code %s\nresult : \n%s", context.argument(),e.exitCode(), NaruUtils.stripAnsi(result))
                                        .withLevel(e.exitCode()!=0?Level.SEVERE : Level.INFO)
                        );
                    });
                }
            }
        });
    }

}
