package net.thevpc.naru.ext.tools.tasks;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.scheduler.NaruEventFilter;
import net.thevpc.naru.api.scheduler.NaruEventFilters;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

public class NaruWaitDirective extends NaruDirectiveBase {

    public NaruWaitDirective() {
        super("wait", "task", "wait for an event or a task completion");
        register(new AbstractSubCommand(new SubCommandHelp("--for=<event> --from=tid|children|parent|siblings",
                "wait for an event from the provided (if any) tasks."
                +"\nwhen no event, waits for termination"
                +"\nfrom-expression:"
                +"\n  any"
                +"\n  parent"
                +"\n  sibling"
                +"\n  child"
                +"\n  child(<taskId>)"
                +"\n  taskId(<taskId>)"
                +"\n  <taskId>"
        )) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NRef<String> eventName = NRef.of();
                NRef<String> fromFilter = NRef.of();
                cmdLine.matcher()
                        .when("--for").asEntry(x -> eventName.set(x.stringValue()))
                        .when("--from").asEntry(x -> fromFilter.set(x.stringValue()))
                        .requireAll();
                NOptional<NaruEventFilter> f = NaruEventFilters.parse(fromFilter.get(),
                        NStringUtils.firstNonBlankStripped(eventName.get(), NaruEvent.TASK_TERMINATED),
                        context.task());
                if (f.isNotPresent()) {
                    NMsg msg = f.message().get();
                    task.throwError(msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                task.awaitFilter(f.get());
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }


}
