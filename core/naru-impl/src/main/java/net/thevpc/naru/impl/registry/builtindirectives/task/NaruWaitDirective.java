package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.scheduler.NaruEventFilter;
import net.thevpc.naru.api.scheduler.NaruEventFilters;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.util.*;

public class NaruWaitDirective extends AbstractDirective {

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
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NRef<String> eventName = NRef.of();
                NRef<String> fromFilter = NRef.of();
                cmdLine.matcher()
                        .with("--for").matchEntry(x -> eventName.set(x.stringValue()))
                        .with("--from").matchEntry(x -> fromFilter.set(x.stringValue()))
                        .requireAll();
                NOptional<NaruEventFilter> f = NaruEventFilters.parse(fromFilter.get(),
                        NStringUtils.firstNonBlankTrimmed(eventName.get(), NaruEvent.TASK_TERMINATED),
                        context.task());
                if (f.isNotPresent()) {
                    task.throwError(f.getMessage().get());
                    return;
                }
                task.awaitFilter(f.get());
            }
        });
    }


}
