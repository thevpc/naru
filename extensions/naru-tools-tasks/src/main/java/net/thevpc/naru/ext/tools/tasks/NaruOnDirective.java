package net.thevpc.naru.ext.tools.tasks;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEventFilter;
import net.thevpc.naru.api.scheduler.NaruEventFilters;
import net.thevpc.naru.api.scheduler.NaruEventSubscription;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.HashMap;
import java.util.Map;

public class NaruOnDirective extends NaruDirectiveBase {

    public NaruOnDirective() {
        super("on", "task", "define an event inbox hook");
        register(new AbstractSubCommand(new SubCommandHelp("<event> <routine> --from=<from-expression> <args>",
                "define an event inbox hook and the routine that shall be called when the event is received."
                        + "\nevent args are accessible as a special 'event.<key>' vars"
                        + "\nevent expression"
                        + "\n  any : any source task"
                        + "\n  <number> : any task with id"
                        + "\n  task(<number>) : any task with id"
                        + "\n  parent : own parent"
                        + "\n  child : any child"
                        + "\n  sibling : any sibling (same parent)"
                        + "\n  child(<number>) : any child of task with id <number>"
        )) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String event = null;
                String routine = null;
                Map<String, Object> args = new HashMap<>();
                String filter = null;
                boolean once = false;
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
                        if (p.key().equals("--from")) {
                            p = cmdLine.nextEntry().get();
                            filter = p.value();
                        } else if (p.key().equals("--once")) {
                            p = cmdLine.nextFlag().get();
                            once = p.booleanValue();
                        } else {
                            NMsg msg = NMsg.ofC("Error on event: missing event and/or routine %s / %s", event, routine);
                            task.throwError(msg);
                            return NaruStmtResult.ofError(msg.toString());
                        }
                    } else {
                        NMsg msg = NMsg.ofC("Error on event: missing event and/or routine %s / %s", event, routine);
                        task.throwError(msg);
                        return NaruStmtResult.ofError(msg.toString());
                    }
                }
                if (NBlankable.isBlank(event) || NBlankable.isBlank(routine)) {
                    NMsg msg = NMsg.ofC("Error on event: missing event and/or routine %s / %s", event, routine);
                    task.throwError(msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NOptional<NaruEventFilter> u = NaruEventFilters.parse(filter, null, task);
                if (!u.isPresent()) {
                    NMsg msg = NMsg.ofC("Error on event: missing event and/or routine %s / %s", event, routine);
                    task.throwError(msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruEventFilter c = NaruEventFilters.and(NaruEventFilters.eventName(event), u.get());
                task.subscribe(event, new NaruEventSubscription(routine, args, c, once));
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }
}
