package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.scheduler.NaruEventFilter;
import net.thevpc.naru.api.scheduler.NaruEventFilters;
import net.thevpc.naru.api.scheduler.NaruEventSubscription;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NaruOnDirective extends AbstractDirective {

    public NaruOnDirective() {
        super("on", "task", "define and event inbox hook");
        register(new AbstractSubCommand(new SubCommandHelp("<event> <routine> --once <args>", "define an event inbox hook and the routine that shall be called when the event is received.\nevent args are accessible as a special 'event.<key>' vars")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<String> li = new ArrayList<>();
                String event = null;
                String routine = null;
                boolean once = false;
                Map<String, Object> args = new HashMap<>();
                String filter = null;
                while (!cmdLine.isEmpty()) {
                    NArg p = cmdLine.peek().get();
                    if (p.isNonOption()) {
                        if (event == null) {
                            p = cmdLine.next().get();
                            event = p.image();
                        } else if (routine == null) {
                            p = cmdLine.next().get();
                            routine = p.image();
                        } else {
                            p = cmdLine.nextEntry().get();
                            args.put(p.key(), p.value());
                        }
                    } else if (p.isOption()) {
                        if (p.key().equals("--once")) {
                            p = cmdLine.nextFlag().get();
                            once = p.booleanValue();
                            if (p.key().equals("--from")) {
                                p = cmdLine.nextEntry().get();
                                filter = p.value();
                            } else {
                                task.throwError(NMsg.ofC("Error on event: missing event and/or routine %s / %s", event, routine));
                                return;
                            }
                        }
                    }else{
                        task.throwError(NMsg.ofC("Error on event: missing event and/or routine %s / %s", event, routine));
                        return;
                    }
                }
                if (NBlankable.isBlank(event) || NBlankable.isBlank(routine)) {
                    task.throwError(NMsg.ofC("Error on event: missing event and/or routine %s / %s", event, routine));
                    return;
                }
                task.subscribe(event, new NaruEventSubscription(routine, args, NaruEventFilters.parse(event, filter, false, task), once));
            }
        });
    }
}
