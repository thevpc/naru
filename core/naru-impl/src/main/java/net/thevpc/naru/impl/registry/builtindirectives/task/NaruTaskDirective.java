package net.thevpc.naru.impl.registry.builtindirectives.task;

import net.thevpc.naru.api.agent.*;
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
import net.thevpc.nuts.util.NLiteral;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class NaruTaskDirective extends AbstractDirective {
    public NaruTaskDirective() {
        super("task", "task", "manage tasks", "tasks","t");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeList(context, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
                case "current": {
                    executeCurrent(context, cmdLine);
                    break;
                }
                case "list": {
                    executeList(context, cmdLine);
                    break;
                }

                case "kill": {
                    executeKill(context, cmdLine);
                    break;
                }
                case "hold": {
                    executeHold(context, cmdLine);
                    break;
                }
                case "unhold": {
                    executeUnhold(context, cmdLine);
                    break;
                }
                case "stacktrace": {
                    executeStacktrace(context, cmdLine);
                    break;
                }
                case "stackframes": {
                    executeStackFrames(context, cmdLine);
                    break;
                }
                case "--help":
                case "help": {
                    executeHelp(context, cmdLine);
                    break;
                }
                default: {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask ctask = context.task();
        int index = 1;
        NaruSession session = context.task().session();
        for (NaruTask task : session.tasks()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s]%s%s %s %s (%s) %s %s %s %s %s", index,
                    ctask.id() == task.id() ? " * " : " ",
                    taskFlags(task)+" ",
                    task.id(),
                    task.parentId()<0?"":task.parentId(),
                    NaruUtils.timeAgo(task.creationTime()),
                    task.isFg()?NMsg.ofStyledPrimary1("FG"):NMsg.ofStyledPrimary2("BG"),
                    task.taskMode(),
                    task.status(),
                    task.schedulerMode(),
                    task.name()
            ));
            index++;
        }
    }
    public void executeCurrent(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Current Task :%s%s %s (%s) %s %s %s %s %s",
                taskFlags(task) + " ",
                task.id(),
                task.parentId()<0?"":task.parentId(),
                NaruUtils.timeAgo(task.creationTime()),
                task.isFg()?NMsg.ofStyledPrimary1("FG"):NMsg.ofStyledPrimary2("BG"),
                task.taskMode(),
                task.status(),
                task.schedulerMode(),
                task.name()
        ));
    }
    public void executeKill(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        if (cmdLine.isEmpty()) {
            return;
        }
        NaruTask task = context.task();
        int count = 0;
        while (!cmdLine.isEmpty()) {
            long a = NLiteral.of(cmdLine.next().get().image()).asLong().orElse(-1L);
            if (a >= 0) {
                NaruTask t = task.session().findTask(a).orNull();
                if (t == null) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task not found %s", a));
                } else {
                    t.kill();
                    count++;
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task killed %s", a));
                }
            }
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("killed %s " + (count == 1 ? "task" : "tasks"), count));
    }

    public void executeHold(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        if (cmdLine.isEmpty()) {
            return;
        }
        NaruTask task = context.task();

        int count = 0;
        while (!cmdLine.isEmpty()) {
            long a = NLiteral.of(cmdLine.next().get().image()).asLong().orElse(-1L);
            if (a >= 0) {
                NaruTask t = task.session().findTask(a).orNull();
                if (t == null) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task not found %s", a));
                } else {
                    if(t.isHeld()){
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task already hold %s", a));
                    }else {
                        t.hold();
                        count++;
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task killed %s", a));
                    }
                }
            }
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("killed %s " + (count == 1 ? "task" : "tasks"), count));
    }
    public void executeUnhold(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        if (cmdLine.isEmpty()) {
            return;
        }
        NaruTask task = context.task();

        int count = 0;
        while (!cmdLine.isEmpty()) {
            long a = NLiteral.of(cmdLine.next().get().image()).asLong().orElse(-1L);
            if (a >= 0) {
                NaruTask t = task.session().findTask(a).orNull();
                if (t == null) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task not found %s", a));
                } else {
                    if(!t.isHeld()){
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task is not hold %s", a));
                    }else {
                        t.unhold();
                        count++;
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("task killed %s", a));
                    }
                }
            }
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("killed %s " + (count == 1 ? "task" : "tasks"), count));
    }

    public void executeStacktrace(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        int index=1;
        for (NaruTaskStackItem naruTaskStackItem : task.stacktrace()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s <%s> %s", index,naruTaskStackItem.index(),naruTaskStackItem.name(),naruTaskStackItem.instruction()));
            index++;
        }
    }

    public void executeStackFrames(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        int index=1;
        for (NaruTaskStackFrame item : task.stackframes()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s <%s> %s", index,item.index(),item.name(),item.instruction()));
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\tparams : %s", item.params().size()));
            for (Map.Entry<String, Object> e : item.params().entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).toList()) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\t\t %s = %s", e.getKey(), e.getValue()));
            }
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\tlocal vars :%s", item.localVars().size()));
            for (Map.Entry<String, Object> e : item.localVars().entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).toList()) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("\t\t %s = %s", e.getKey(), e.getValue()));
            }
            index++;
        }
    }

    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask session = context.task();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary5(name()));
        session.log(NaruLogMode.AGENT_RESPONSE, kk);
        session.log(NaruLogMode.AGENT_RESPONSE, kk);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("list")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list tasks"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("current")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           display current task"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <id>", kk, NMsg.ofStyledPrimary4("kill")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           kill tasks"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <id>", kk, NMsg.ofStyledPrimary4("hold")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           hold tasks"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <id>", kk, NMsg.ofStyledPrimary4("unhold")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           unhold tasks"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("stacktrace")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show stacktrace"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("stackframes")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show stack frames"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }



    private static String taskFlags(NaruTask task) {
        StringBuilder sb = new StringBuilder();
        if(task.isHeld()){
            sb.append("H");
        }else{
            sb.append(".");
        }
        if(task.isFg()){
            sb.append("F");
        }else{
            sb.append(".");
        }
        switch (task.taskMode()){
            case INTERACTIVE:
                sb.append("I");
                break;
            case BATCH:
                sb.append(".");
                break;
            default:{
                sb.append(".");
            }
        }
        switch (task.status()){
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
            case BLOCKED_ON_TASK:
                sb.append("T");
                break;
            case BLOCKED_ON_INPUT:
                sb.append("I");
                break;
            case BLOCKED_ON_EVENT:
                sb.append("E");
                break;
            default:{
                sb.append(".");
            }
        }
        switch (task.schedulerMode()){
            case AUTO:
                sb.append(".");
                break;
            case STEP:
                sb.append("S");
                break;
            case THROTTLED:
                sb.append("T");
                break;
            default:{
                sb.append(".");
            }
        }
        return sb.toString();
    }


    @Override
    public List<NArgCandidate> resolveCandidates(
            NCmdLine cmdLine,
            NCmdLineAutoCompleteResolver.Pos pos,
            NaruSession session) {
        List<NArgCandidate> candidates = new ArrayList<>();
        String[] stringArray = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String currentArg = wordIndex < stringArray.length ? stringArray[wordIndex] : "";

        if (wordIndex == 1) {
            addCandidates(candidates, currentArg, "current", "list", "kill", "hold", "unhold", "help");
        }
        return candidates;
    }
}
