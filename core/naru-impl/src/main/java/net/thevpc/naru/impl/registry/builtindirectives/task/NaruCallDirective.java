package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;

public class NaruCallDirective extends AbstractDirective {

    public NaruCallDirective() {
        super("call", "task", "call routine");
        register(new AbstractSubCommand(new SubCommandHelp("<routine>...", "call one or more routines in the current task as a new frame\n<routine> can be routine name or routine path")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<String> li = new ArrayList<>();
                cmdLine.matcher()
                        .withNonOption().matchAny(a -> {
                            String s = a.image();
                            NaruRoutine rtn;
                            rtn = task.session().routine(s, task,false).orNull();
                            if (rtn == null) {
                                task.throwError(NMsg.ofC("Error statement: routine not found %s", s));
                                return;
                            }
                            li.add(rtn.name());
                        })
                        .requireAll();
                for (int i = li.size() - 1; i >= 0; i--) {
                    task.pushFrame(0, null, li.get(i),false);
                }
            }
        });
    }

}
