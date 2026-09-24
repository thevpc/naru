package net.thevpc.naru.ext.tools.sessions;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

public class NaruReloadDirective extends NaruDirectiveBase {
    public NaruReloadDirective() {
        super("reload","session", "reload from last saved");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                task.session().reload();
                context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("reloaded session."));
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }

}