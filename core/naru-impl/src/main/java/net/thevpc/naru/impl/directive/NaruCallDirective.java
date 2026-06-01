package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;

public class NaruCallDirective extends AbstractDirective {

    public NaruCallDirective() {
        super("call", "general", "call routine");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        List<String> li = new ArrayList<>();
        cmdLine.matcher()
                .withNonOption().matchAny(a->{
                    String s = a.image();
                    NaruRoutine rtn;
                    rtn = task.session().routineManager().routineOrUnnumberedRoutine(s, task).orNull();
                    if (rtn == null) {
                        task.throwError(NMsg.ofC("Error statement: routine not found %s", s));
                        return;
                    }
                    li.add(rtn.getName());
                })
                .requireAll();
        for (int i = li.size() - 1; i >= 0; i--) {
            task.pushFrame(0,null,li.get(i));
        }
    }

}
