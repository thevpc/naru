package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.scheduler.NaruEventRouting;
import net.thevpc.naru.api.scheduler.NaruEventTarget;
import net.thevpc.naru.api.scheduler.NaruEventTargets;
import net.thevpc.naru.api.scheduler.NaruRetentionPolicy;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NNameFormat;
import net.thevpc.nuts.util.NStringUtils;

import java.time.Instant;
import java.util.*;

public class NaruFireDirective extends AbstractDirective {

    public NaruFireDirective() {
        super("fire", "task", "fire an event to self or another task");
        register(new AbstractSubCommand(new SubCommandHelp("<event> [--to=(<task_id>|parent|children|self|siblings)...] [<event-arg-key>=<value>...]", "send an event to self or one or more task an event inbox hook and the routine that shall be called when the event is received")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruEventTarget target = null;
                String event = null;
                Map<String, Object> args = new HashMap<>();
                while (!cmdLine.isEmpty()) {
                    if (event == null) {
                        event = cmdLine.next().get().image();
                    } else {
                        NArg a = cmdLine.nextEntry().get();
                        switch (a.key()) {
                            case "--to": {
                                NaruEventTarget o = NaruEventTargets.parse(a.value(), task);
                                target = NaruEventTargets.or(target,o);
                                break;
                            }
                            case "--keep": {
                                NaruRetentionPolicy o = NaruRetentionPolicies.parse(a.value(), task);
                                target = NaruRetentionPolicies.or(target,o);
                                break;
                            }
                            default: {
                                if (!a.isOption()) {
                                    args.put(a.key(), a.value());
                                }
                            }
                        }
                    }
                }

                if (NBlankable.isBlank(event)) {
                    task.throwError(NMsg.ofC("Error on event: missing event %s", event));
                    return;
                }
                NaruEvent ne=new NaruEvent(
                        event,args,task.id(),task.parentId(), Instant.now(),

                )
                task.fireEvent(event, args, li.toArray(new NaruEventRouting[0]));
            }
        });
    }

}
