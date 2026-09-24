package net.thevpc.naru.ext.tools.tasks;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;

public class NaruSleepDirective extends NaruDirectiveBase {

    public NaruSleepDirective() {
        super("sleep", "task", "sleep current task");
        register(new AbstractSubCommand(new SubCommandHelp("<duration>", "sleep current task for the given duration")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NArg a = cmdLine.next().orNull();
                if(a==null){
                    task.sleep(NDuration.ofSeconds(1));
                }else{
                    NDuration d = NDuration.of(a.image()).orNull();
                    if(d==null){
                        NMsg msg = NMsg.ofC("Error on sleep: invalid sleep duration : %s", a.image());
                        task.throwError(msg);
                        return NaruStmtResult.ofError(msg.toString());
                    }
                    task.sleep(d);
                }
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }

}
