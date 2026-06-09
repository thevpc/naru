package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class NaruStartDirective extends AbstractDirective {

    public NaruStartDirective() {
        super("start", "task", "start new task");
        register(new AbstractSubCommand(new SubCommandHelp("<routine>...", "start one or more routines as a single consecutive new task\n<routine> can be routine name or routine path")) {
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
                            li.addAll(rtn.getIndexedLines().stream().map(x -> x.command()).collect(Collectors.toList()));
                        })
                        .requireAll();
                NaruTask tt = task.session().newTask(
                                NaruTaskSpec.of()
                                        .parentId(context.task().id())
                                        .statements(li.toArray(new String[0]))
                                        .resolveName()
                        )
                        .bg()
                        .unhold();
                context.task().frame().lastResult(NaruStmtResult.ofSuccess(tt.id()));
            }
        });
    }


}
