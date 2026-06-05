package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.time.Instant;
import java.util.*;

public class NaruFireDirective extends AbstractDirective {

    public NaruFireDirective() {
        super("fire", "task", "fire an event to self or another task");
        register(new AbstractSubCommand(new SubCommandHelp("<event> [--to=<target-expression>...]  [--keep=<policy-expression>...] [<event-arg-key>=<value>...]",
                "send an event to self or one or more task an event inbox hook and the routine that shall be called when the event is received"
                        + "\ntarget-expression:"
                        + "\n  all      : all tasks"
                        + "\n  children : children tasks"
                        + "\n  children : children tasks"
                        + "\n  siblings : siblings tasks"
                        + "\n  parent   : parent tasks"
                        + "\n  <number> : task with id"
                        + "\n  & operator : 'and' expression like in 'parent & children'"
                        + "\n  | operator : 'or' expression like in 'parent | children'"
                        + "\npolicy-expression:"
                        + "\n  forever     : retain forever"
                        + "\n  once        : retain until consumed once"
                        + "\n  max(<nbr>)  : retain until consumed <nbr> times"
                        + "\n  ttl(<nbr>)  : retain for max <nbr> seconds"
                        + "\n  ttl(<duration>)  : retain for max <duration> as string. ex : '3s'"
                        + "\n  default  : shortcut for once|tt('1h')"
                        + "\n  & operator : 'and' expression like in 'once & tt('1h')'"
                        + "\n  | operator : 'or' expression like in 'once | tt('1h')'"
        )) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruEventTarget target = null;
                NaruRetentionPolicy policy = null;
                String event = null;
                Map<String, Object> payload = new HashMap<>();
                while (!cmdLine.isEmpty()) {
                    if (event == null) {
                        event = cmdLine.next().get().image();
                    } else {
                        NArg a = cmdLine.nextEntry().get();
                        switch (a.key()) {
                            case "--to": {
                                NOptional<NaruEventTarget> oo = NaruEventTargets.parse(a.value(), task);
                                if (oo.isNotPresent()) {
                                    task.throwError(NMsg.ofC("Error on event: event %s : %s", event, oo.getMessage().get()));
                                    return;
                                }
                                target = NaruEventTargets.or(target, oo.get());
                                break;
                            }
                            case "--keep": {
                                NOptional<NaruRetentionPolicy> oo = NaruRetentionPolicies.parse(a.value(), task);
                                if (oo.isNotPresent()) {
                                    task.throwError(NMsg.ofC("Error on event: event %s : %s", event, oo.getMessage().get()));
                                    return;
                                }
                                policy = NaruRetentionPolicies.or(policy, oo.get());
                                break;
                            }
                            default: {
                                if (!a.isOption()) {
                                    payload.put(a.key(), a.value());
                                }
                            }
                        }
                    }
                }
                if (NBlankable.isBlank(event)) {
                    task.throwError(NMsg.ofC("Error on event: missing event %s", event));
                    return;
                }
                task.fireEvent(event, payload, target, policy);
            }
        });
    }

}
