package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.List;

public class NaruSourceDirective extends AbstractDirective {

    public NaruSourceDirective() {
        super("source", "task", "inline routine");
        register(new AbstractSubCommand(new SubCommandHelp("<routine>...", "inline and run one or more routines in the current task\n<routine> can be routine name or routine path")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<List<NaruStatement>> li = new ArrayList<>();
                cmdLine.matcher()
                        .withNonOption().matchAny(a->{
                            String s = a.image();
                            NaruRoutine rtn;
                            rtn = task.session().routine(s, task,false).orNull();
                            if (rtn == null) {
                                task.throwError(NMsg.ofC("Error statement: routine not found %s", s));
                                return;
                            }
                            NOptional<List<NaruStatement>> ll = rtn.parseStatements(task);
                            if (!ll.isPresent()) {
                                task.throwError(ll.getMessage().get());
                                return;
                            }
                            li.add(ll.get());
                        })
                        .requireAll();
                for (int i = li.size() - 1; i >= 0; i--) {
                    task.prependStatements(li.get(i).toArray(new NaruStatement[0]));
                }
            }
        });
    }

}
