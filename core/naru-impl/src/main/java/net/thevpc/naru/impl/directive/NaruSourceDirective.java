package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.List;

public class NaruSourceDirective extends AbstractDirective {

    public NaruSourceDirective() {
        super("source", "general", "inline routine");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        List<List<NaruStatement>> li = new ArrayList<>();
        cmdLine.matcher()
                .withNonOption().matchAny(a->{
                    String s = a.image();
                    NaruRoutine rtn;
                    rtn = task.session().routineManager().routineOrUnnumberedRoutine(s, task).orNull();
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

}
