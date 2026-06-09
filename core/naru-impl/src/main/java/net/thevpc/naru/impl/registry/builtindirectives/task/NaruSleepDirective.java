package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;

public class NaruSleepDirective extends AbstractDirective {

    public NaruSleepDirective() {
        super("sleep", "task", "sleep current task");
        register(new AbstractSubCommand(new SubCommandHelp("<duration>", "sleep current task for the given duration")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NArg a = cmdLine.next().orNull();
                if(a==null){
                    task.sleep(NDuration.ofSeconds(1));
                }else{
                    NDuration d = NDuration.parse(a.image()).orNull();
                    if(d==null){
                        task.throwError(NMsg.ofC("Error on sleep: invalid sleep duration : %s", a.image()));
                        return;
                    }
                    task.sleep(d);
                }
            }
        });
    }

}
