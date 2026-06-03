package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.task.NaruTaskStackFrame;
import net.thevpc.naru.api.task.NaruTaskStackItem;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NLiteral;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class NaruTaskDirective extends AbstractDirective {
    public NaruTaskDirective() {
        super("task", "task", "manage tasks", "tasks", "t");
        noCommand("list");
        register(new AbstractSubCommand("current", NText.ofPlain("display current task")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();

                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Current Task :%s%s %s (%s) %s %s %s %s %s",
                        taskFlags(task) + " ",
                        task.id(),
                        task.parentId() < 0 ? "" : task.parentId(),
                        NaruUtils.timeAgo(task.creationTime()),
                        task.isFg() ? NMsg.ofStyledPrimary1("FG") : NMsg.ofStyledPrimary2("BG"),
                        task.taskMode(),
                        task.status(),
                        task.schedulerMode(),
                        task.name()
                ));
                task.frame().setLastResult(NaruStmtResult.ofSuccess(task.id()));
            }
        });
        register(new AbstractSubCommand("list", NText.ofPlain("list current tasks")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask ctask = context.task();
                int index = 1;
                NaruSession session = context.task().session();
                for (NaruTask task : session.tasks()) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s]%s%s %s %s (%s) %s %s %s %s %s", index,
                            ctask.id() == task.id() ? " * " : " ",
                            taskFlags(task) + " ",
                            task.id(),
                            task.parentId() < 0 ? "" : task.parentId(),
                            NaruUtils.timeAgo(task.creationTime()),
                            task.isFg() ? NMsg.ofStyledPrimary1("FG") : NMsg.ofStyledPrimary2("BG"),
                            task.taskMode(),
                            task.status(),
                            task.schedulerMode(),
                            task.name()
                    ));
                    index++;
                }
                context.task().frame().setLastResult(NaruStmtResult.ofSuccess(
                        session.tasks().stream().mapToLong(x -> x.id()).toArray()
                ));
            }
        });
        register(new AbstractSubCommand("kill", NText.ofPlain("kill one or more tasks"),
                new SubCommandHelp("<id>...", "kills tasks of with the provided task ids")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                if (cmdLine.isEmpty()) {
                    return;
                }
                NaruTask task = context.task();
                List<Long> collected = new ArrayList<>();
                while (!cmdLine.isEmpty()) {
                    long a = NLiteral.of(cmdLine.next().get().image()).asLong().orElse(-1L);
                    if (a >= 0) {
                        NaruTask t = task.session().findTask(a).orNull();
                        if (t == null) {
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task not found %s", a));
                        } else {
                            t.kill();
                            collected.add(t.id());
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task killed %s", a));
                        }
                    }
                }
                context.task().frame().setLastResult(NaruStmtResult.ofSuccess(
                        collected.stream().mapToLong(x -> x).toArray()
                ));
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("killed %s " + (collected.size() == 1 ? "task" : "tasks"), collected.size()));
            }
        });
        register(new AbstractSubCommand("hold", NText.ofPlain("hold one or more tasks"),
                new SubCommandHelp("<id>...", "hold (pause) tasks of with the provided task ids")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                if (cmdLine.isEmpty()) {
                    return;
                }
                NaruTask task = context.task();

                List<Long> collected = new ArrayList<>();
                while (!cmdLine.isEmpty()) {
                    long a = NLiteral.of(cmdLine.next().get().image()).asLong().orElse(-1L);
                    if (a >= 0) {
                        NaruTask t = task.session().findTask(a).orNull();
                        if (t == null) {
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task not found %s", a));
                        } else {
                            if (t.isHeld()) {
                                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task already hold %s", a));
                            } else {
                                t.hold();
                                collected.add(t.id());
                                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task hold %s", a));
                            }
                        }
                    }
                }

                context.task().frame().setLastResult(NaruStmtResult.ofSuccess(collected.stream().mapToLong(x -> x).toArray()));
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("killed %s " + (collected.size() == 1 ? "task" : "tasks"), collected.size()));
            }
        });
        register(new AbstractSubCommand("unhold", NText.ofPlain("unhold one or more tasks"),
                new SubCommandHelp("<id>...", "unhold (pause) tasks of with the provided task ids")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                if (cmdLine.isEmpty()) {
                    return;
                }
                NaruTask task = context.task();
                List<Long> collected = new ArrayList<>();
                while (!cmdLine.isEmpty()) {
                    long a = NLiteral.of(cmdLine.next().get().image()).asLong().orElse(-1L);
                    if (a >= 0) {
                        NaruTask t = task.session().findTask(a).orNull();
                        if (t == null) {
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task not found %s", a));
                        } else {
                            if (!t.isHeld()) {
                                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task is not hold %s", a));
                            } else {
                                t.unhold();
                                collected.add(t.id());
                                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task killed %s", a));
                            }
                        }
                    }
                }
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("unhold %s " + (collected.size() == 1 ? "task" : "tasks"), collected.size()));
                context.task().frame().setLastResult(NaruStmtResult.ofSuccess(collected.stream().mapToLong(x -> x).toArray()));
            }
        });

        register(new AbstractSubCommand("stacktrace", NText.ofPlain("show stacktrace of one or more tasks"),
                new SubCommandHelp("[<id>...]", "show stacktrace of the given task ids\nwhen no id is provided, shows stacktrace of the current task")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                List<String> collected = new ArrayList<>();
                if (cmdLine.isEmpty()) {
                    NaruTask task = context.task();
                    int index = 1;
                    for (NaruTaskStackItem naruTaskStackItem : task.stacktrace()) {
                        NMsg msg = NMsg.ofC("[%s] %s <%s> %s", index, naruTaskStackItem.index(), naruTaskStackItem.name(), naruTaskStackItem.instruction());
                        task.log(NaruLogMode.AGENT_RESPONSE, msg);
                        collected.add(msg.toString());
                        index++;
                    }
                } else {
                    NaruTask task = context.task();
                    int count = 0;
                    while (!cmdLine.isEmpty()) {
                        long a = NLiteral.of(cmdLine.next().get().image()).asLong().orElse(-1L);
                        if (a >= 0) {
                            NaruTask t = task.session().findTask(a).orNull();
                            if (t == null) {
                                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task not found %s", a));
                            } else {
                                count++;
                                int index = 1;
                                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Task %s", t.id()));
                                collected.add(NMsg.ofC("Task %s", t.id()).toString());
                                for (NaruTaskStackItem naruTaskStackItem : t.stacktrace()) {
                                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s <%s> %s", index, naruTaskStackItem.index(), naruTaskStackItem.name(), naruTaskStackItem.instruction()));
                                    collected.add(NMsg.ofC("[%s] %s <%s> %s", index, naruTaskStackItem.index(), naruTaskStackItem.name(), naruTaskStackItem.instruction()).toString());
                                    index++;
                                }
                            }
                        }
                    }
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("stacks of %s " + (count == 1 ? "task" : "tasks"), count));
                    context.task().frame().setLastResult(NaruStmtResult.ofSuccess(collected.stream().toArray(String[]::new)));
                }
            }
        });
        register(new AbstractSubCommand("frames", NText.ofPlain("show frames (all debug infos, including vars, params...) of one or more tasks"),
                new SubCommandHelp("[<id>...]", "show frames (all debug infos, including vars, params...) of the given task ids\nwhen no id is provided, shows frames of the current task")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                List<String> collected = new ArrayList<>();
                if (cmdLine.isEmpty()) {
                    NaruTask task = context.task();
                    int index = 1;
                    for (NaruTaskStackFrame item : task.stackframes()) {
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s <%s> %s", index, item.index(), item.name(), item.instruction()));
                        collected.add(NMsg.ofC("[%s] %s <%s> %s", index, item.index(), item.name(), item.instruction()).toString());
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\tparams : %s", item.params().size()));
                        collected.add(NMsg.ofC("\tparams : %s", item.params().size()).toString());
                        for (Map.Entry<String, Object> e : item.params().entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).toList()) {
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\t\t %s = %s", e.getKey(), e.getValue()));
                            collected.add(NMsg.ofC("\t\t %s = %s", e.getKey(), e.getValue()).toString());
                        }
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\tlocal vars :%s", item.localVars().size()));
                        for (Map.Entry<String, Object> e : item.localVars().entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).toList()) {
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\t\t %s = %s", e.getKey(), e.getValue()));
                            collected.add(NMsg.ofC("\t\t %s = %s", e.getKey(), e.getValue()).toString());
                        }
                        index++;
                    }
                } else {
                    NaruTask task = context.task();
                    int count = 0;
                    while (!cmdLine.isEmpty()) {
                        long a = NLiteral.of(cmdLine.next().get().image()).asLong().orElse(-1L);
                        if (a >= 0) {
                            NaruTask t = task.session().findTask(a).orNull();
                            if (t == null) {
                                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task not found %s", a));
                            } else {
                                count++;
                                int index = 1;
                                for (NaruTaskStackFrame item : t.stackframes()) {
                                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s <%s> %s", index, item.index(), item.name(), item.instruction()));
                                    collected.add(NMsg.ofC("[%s] %s <%s> %s", index, item.index(), item.name(), item.instruction()).toString());
                                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\tparams : %s", item.params().size()));
                                    collected.add(NMsg.ofC("\tparams : %s", item.params().size()).toString());
                                    for (Map.Entry<String, Object> e : item.params().entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).toList()) {
                                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\t\t %s = %s", e.getKey(), e.getValue()));
                                        collected.add(NMsg.ofC("\t\t %s = %s", e.getKey(), e.getValue()).toString());
                                    }
                                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\tlocal vars :%s", item.localVars().size()));
                                    collected.add(NMsg.ofC("\tlocal vars :%s", item.localVars().size()).toString());
                                    for (Map.Entry<String, Object> e : item.localVars().entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).toList()) {
                                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\t\t %s = %s", e.getKey(), e.getValue()));
                                        collected.add(NMsg.ofC("\t\t %s = %s", e.getKey(), e.getValue()).toString());
                                    }
                                    index++;
                                }
                            }
                        }
                    }
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("frames of %s " + (count == 1 ? "task" : "tasks"), count));
                    context.task().frame().setLastResult(NaruStmtResult.ofSuccess(collected.stream().toArray(String[]::new)));
                }
            }
        });
    }


    private static String taskFlags(NaruTask task) {
        StringBuilder sb = new StringBuilder();
        if (task.isHeld()) {
            sb.append("H");
        } else {
            sb.append(".");
        }
        if (task.isFg()) {
            sb.append("F");
        } else {
            sb.append(".");
        }
        switch (task.taskMode()) {
            case INTERACTIVE:
                sb.append("I");
                break;
            case BATCH:
                sb.append(".");
                break;
            default: {
                sb.append(".");
            }
        }
        switch (task.status()) {
            case DONE:
                sb.append("D");
                break;
            case RUNNING:
                sb.append(".");
                break;
            case READY:
                sb.append("R");
                break;
            case KILLED:
                sb.append("K");
                break;
            case FAILED:
                sb.append("F");
                break;
            case BLOCKED_ON_INPUT:
                sb.append("I");
                break;
            case BLOCKED_ON_EVENT:
                sb.append("E");
                break;
            default: {
                sb.append(".");
            }
        }
        switch (task.schedulerMode()) {
            case AUTO:
                sb.append(".");
                break;
            case STEP:
                sb.append("S");
                break;
            case THROTTLED:
                sb.append("T");
                break;
            default: {
                sb.append(".");
            }
        }
        return sb.toString();
    }

}
