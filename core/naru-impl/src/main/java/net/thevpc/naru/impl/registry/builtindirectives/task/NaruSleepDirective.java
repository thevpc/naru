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
        super("sleep", "task", "define and event inbox hook");
        register(new AbstractSubCommand(new SubCommandHelp("<event> <routine> <args>", "define an event inbox hook and the routine that shall be called when the event is received.\nevent args are accessible as a special 'event.<key>' vars")) {
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
