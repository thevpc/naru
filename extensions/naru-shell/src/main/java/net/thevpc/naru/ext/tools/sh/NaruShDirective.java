package net.thevpc.naru.ext.tools.sh;

import net.thevpc.naru.api.access.NaruAccessDecision;
import net.thevpc.naru.api.access.NaruAccessGuard;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.io.NAnsiTermHelper;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NRef;

import java.util.logging.Level;

public class NaruShDirective extends NaruDirectiveBase {
    public NaruShDirective() {
        super("sh","general", "run shell command");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruResource shell = NaruResource.shell(context.argument());
                NaruAccessDecision access = NaruAccessGuard.check(task, shell, NaruAction.EXECUTE);
                if (!access.allowed()) {
                    String denied = NaruAccessGuard.denyMessage(task, shell, NaruAction.EXECUTE, access);
                    return NaruStmtResult.ofError(denied);
                }
                final NRef<NaruStmtResult> outcome = NRef.of();
                try (NSession session = NSession.of().copy()) {
                    session.logTermLevel(Level.OFF);
                    session.runWith(() -> {
                        NExec e = NExec.of("nsh","--progress=none", "-c", context.argument()).directory(task.workingDir()).failFast(false);
                        String result = e
                                .grabbedAll();
                        task.addResultMessage(
                                NMsg.ofC("call   : nsh -c %s\nexit code %s\nresult : \n%s", context.argument(),e.exitCode(), NAnsiTermHelper.of().stripAnsi(result))
                                        .withLevel(e.exitCode()!=0?Level.SEVERE : Level.INFO)
                        );
                        outcome.set(NaruStmtResult.of(result == null ? "" : result.trim(), e.exitCode()));
                    });
                }
                return outcome.get();
            }
        });
    }

}