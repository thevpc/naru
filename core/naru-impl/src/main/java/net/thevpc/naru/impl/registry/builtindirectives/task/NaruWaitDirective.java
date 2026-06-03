package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.scheduler.NaruEventFilter;
import net.thevpc.naru.api.scheduler.NaruEventFilters;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.util.*;

import java.util.HashSet;
import java.util.Set;

public class NaruWaitDirective extends AbstractDirective {

    public NaruWaitDirective() {
        super("wait", "task", "wait for an event or a task completion");
        register(new AbstractSubCommand(new SubCommandHelp("[<event>] [--task=tid|children|parent|siblings]", "wait for an event from the provided (if any) tasks.\nwhen no event, waits for termination")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NRef<String> event = NRef.of();
                NRef<String> tasks = NRef.of();
                NRef<Boolean> any = NRef.of();
                cmdLine.matcher()
                        .with("--for").matchEntry(x->tasks.set(x.stringValue()))
                        .with("--any").matchFlag(x->any.set(x.booleanValue()))
                        .withNonOption().matchAny(x->event.set(x.stringValue()))
                        .requireAll();
                task.awaitFilter(NaruEventFilters.parse(tasks.get(),event.get(),any.get(),context.task()));
            }
        });
    }



}
