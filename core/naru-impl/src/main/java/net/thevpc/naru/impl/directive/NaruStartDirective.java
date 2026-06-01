package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class NaruStartDirective extends AbstractDirective {

    public NaruStartDirective() {
        super("start", "general", "start routine in new task");
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
        String name = "task";
        if (li.size() == 1) {
            String a = li.get(0);
            name = NPath.of(a).nameParts().baseName();
        }
        task.session().newTask(context.task().id(), context.task().workingDir(), li.toArray(new String[0]))
                .bg()
                .name(name)
                .unhold();
    }

}
