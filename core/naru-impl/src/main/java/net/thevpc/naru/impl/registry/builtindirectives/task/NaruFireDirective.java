package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.scheduler.NaruEventRouting;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NNameFormat;
import net.thevpc.nuts.util.NStringUtils;

import java.util.*;

public class NaruFireDirective extends AbstractDirective {

    public NaruFireDirective() {
        super("fire", "task", "fire an event to self or another task");
        register(new AbstractSubCommand(new SubCommandHelp("<event> [--to=(<task_id>|parent|children|self|siblings)...] [<event-arg-key>=<value>...]", "send an event to self or one or more task an event inbox hook and the routine that shall be called when the event is received")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<NaruEventRouting> li = new ArrayList<>();
                String event = null;
                Map<String, Object> args = new HashMap<>();
                while (!cmdLine.isEmpty()) {
                    if (event == null) {
                        event = cmdLine.next().get().image();
                    } else {
                        NArg a = cmdLine.nextEntry().get();
                        switch (a.key()) {
                            case "--to": {
                                for (String s : NStringUtils.split(a.value(), ",;|")) {
                                    String ss = NNameFormat.LOWER_KEBAB_CASE.format(s);
                                    switch (ss) {
                                        case "parent": {
                                            li.add(NaruEventRouting.parent());
                                            break;
                                        }
                                        case "self": {
                                            li.add(NaruEventRouting.self());
                                            break;
                                        }
                                        case "children": {
                                            li.add(NaruEventRouting.children());
                                            break;
                                        }
                                        case "siblings": {
                                            li.add(NaruEventRouting.siblings());
                                            break;
                                        }
                                        case "all": {
                                            li.add(NaruEventRouting.all());
                                            break;
                                        }
                                        default: {
                                            li.add(NaruEventRouting.of(NLiteral.of(s).asLong().get()));
                                        }
                                    }
                                }
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

                task.fireEvent(event, args, li.toArray(new NaruEventRouting[0]));
            }
        });
    }

}
