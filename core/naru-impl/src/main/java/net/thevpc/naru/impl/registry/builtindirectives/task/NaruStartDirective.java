package net.thevpc.naru.impl.registry.builtindirectives.task;

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
        super("start", "task", "start routine in new task");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        List<String> li = new ArrayList<>();
        cmdLine.matcher()
                .withNonOption().matchAny(a -> {
                    String s = a.image();
                    NaruRoutine rtn;
                    rtn = task.session().routineManager().routineOrUnnumberedRoutine(s, task).orNull();
                    if (rtn == null) {
                        task.throwError(NMsg.ofC("Error statement: routine not found %s", s));
                        return;
                    }
                    li.addAll(rtn.getIndexedLines().stream().map(x -> x.command()).collect(Collectors.toList()));
                })
                .requireAll();
        task.session().newTask(
                NaruTaskSpec.of()
                        .parentId(context.task().id())
                        .statements(li.toArray(new String[0]))
                        .resolveName()
                )
                .bg()
                .unhold();
    }

}
