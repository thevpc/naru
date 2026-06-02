package net.thevpc.naru.impl.registry.builtindirectives.routine;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NStringUtils;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class NaruRoutineDirective extends AbstractDirective {
    public NaruRoutineDirective() {
        super("routine", "routine", "create, update , list and run  routines", "routines");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeShow(context, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
                case "name": {
                    executeName(context, cmdLine);
                    break;
                }
                case "show": {
                    executeShow(context, cmdLine);
                    break;
                }
                case "list": {
                    executeList(context, cmdLine);
                    break;
                }
                case "drop": {
                    executeDrop(context, cmdLine);
                    break;
                }
                case "clear": {
                    executeClear(context, cmdLine);
                    break;
                }
                case "load":
                case "select":
                {
                    executeLoad(context, cmdLine);
                    break;
                }
                case "unload": {
                    executeUnload(context, cmdLine);
                    break;
                }
                case "run": {
                    executeRun(context, cmdLine);
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
        NaruTask task = context.task();
        List<NaruResourceInfo> naruResourceInfos = task.session().routineManager().list();
        naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationDate(), Comparator.reverseOrder()));
        int index = 1;
        for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %s %s", index,
                    NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                    NMsg.ofStyledPrimary1(naruResourceInfo.getUuid()),
                    NMsg.ofStyledString(naruResourceInfo.getName()))
            );
            index++;
        }
    }

    public void executeShow(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();

        NaruRoutineManager sm = task.session().routineManager();
        NaruRoutine cs = sm.getCurrentRoutine();

        Set<Integer> toShow = new HashSet<>();
        int historySize = task.context(NaruSource.USER).messages().size();
        while (!cmdLine.isEmpty()) {
            String a = cmdLine.next().get().image();
            for (String range : a.split(",;")) {
                range = range.trim();
                if (!range.isEmpty()) {
                    if (range.matches("[0-9]+")) {
                        toShow.add(Integer.parseInt(range) - 1);
                    } else if (range.matches("[0-9]+[-][0-9]+")) {
                        String[] ss = range.split("-");
                        int x = Integer.parseInt(ss[0]) - 1;
                        int y = Integer.parseInt(ss[1]);
                        if (x < 0) {
                            x = historySize + x;
                        }
                        if (y < 0) {
                            y = historySize + y + 1;
                        }
                        for (int i = x; i <= y; i++) {
                            toShow.add(i);
                        }
                    } else {
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid position to drop", range).asError());
                        return;
                    }
                }
            }
        }
        Map.Entry<Integer, String> last = null;
        for (Map.Entry<Integer, String> e : cs.getLinesSet().entrySet()) last = e;
        Integer lastKey = last == null ? 0 : last.getKey();
        if (toShow.isEmpty()) {
            if (!cs.getLinesSet().isEmpty()) {
                for (Map.Entry<Integer, String> e : cs.getLinesSet().entrySet()) {
                    logRow(e.getKey(), lastKey, e.getValue(), task);
                }
            }
        } else {
            List<Integer> bb = toShow.stream().sorted().collect(Collectors.toList());
            for (int k = 0; k < bb.size(); k++) {
                int i = bb.get(k);
                String s = cs.getLinesSet().get(i);
                if (s != null) {
                    logRow(i, lastKey, s, task);
                }
            }
        }
    }

    private void logRow(int rowIndex, int max, String a, NaruTask task) {
        int zeros = (int) Math.ceil(Math.log10(max));
        if (zeros <= 0) {
            zeros = 1;
        }
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));

        List<String> lines = NStringUtils.splitLines(a);
        for (int j = 0; j < lines.size(); j++) {
            String line = lines.get(j);
            if (j == 0) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s",
                        NMsg.ofStyledNumber(zformat.format(rowIndex)),
                        line));
            } else {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        %s", line));
            }
        }
    }

    public void executeClear(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruRoutineManager sm = task.session().routineManager();
        NaruRoutine cs = sm.getCurrentRoutine();
        int count = cs.clear();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s lines", count));
    }

    public void executeDrop(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        if (cmdLine.isEmpty()) {
            return;
        }
        NaruTask task = context.task();
        NaruRoutineManager sm = task.session().routineManager();
        NaruRoutine cs = sm.getCurrentRoutine();

        Set<Integer> toRemove = new HashSet<>();
        int historySize = task.context(NaruSource.USER).messages().size();
        while (!cmdLine.isEmpty()) {
            String a = cmdLine.next().get().image();
            for (String range : a.split(",;")) {
                range = range.trim();
                if (!range.isEmpty()) {
                    if (range.matches("[0-9]+")) {
                        toRemove.add(Integer.parseInt(range) - 1);
                    } else if (range.matches("[0-9]+[-][0-9]+")) {
                        String[] ss = range.split("-");
                        int x = Integer.parseInt(ss[0]) - 1;
                        int y = Integer.parseInt(ss[1]);
                        if (x < 0) {
                            x = historySize + x;
                        }
                        if (y < 0) {
                            y = historySize + y + 1;
                        }
                        for (int i = x; i <= y; i++) {
                            toRemove.add(i);
                        }
                    } else {
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid position to drop", range).asError());
                        return;
                    }
                }
            }
        }
        List<Integer> a = toRemove.stream().sorted(Comparator.<Integer>naturalOrder().reversed()).collect(Collectors.toList());
        List<Integer> b = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            if (cs.removeLine(a.get(i))) {
                b.add(a.get(i));
            }
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s lines", b.size()));
    }

    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary5(name()));
        task.log(NaruLogMode.AGENT_RESPONSE, kk);
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("list")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <n1>-<p1>,<n2>-<p2>", kk, NMsg.ofStyledPrimary4("list")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list intervals"));

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <n1>-<p1>,<n2>-<p2>", kk, NMsg.ofStyledPrimary4("drop")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           drop intervals"));

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("clear")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           clear routine"));

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <name>", kk, NMsg.ofStyledPrimary4("load")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           load routine (or default 'main')"));

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("unload")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           unload routine (back to 'main')"));

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("run")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           run routine"));

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("name")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show routine name"));

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }

    public void executeLoad(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruRoutineManager sm = task.session().routineManager();
        String name = context.argument();
        if (name.isEmpty()) {
            name = "main";
        }
        sm.switchRoutine(name);
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded routine context: %s", name));
    }

    public void executeUnload(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruRoutineManager sm = task.session().routineManager();
        sm.switchRoutine("main");
        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("Unloaded routine context. Back to main."));
    }

    public void executeRun(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruRoutineManager sm = task.session().routineManager();
        task.invokeRoutine(sm.getCurrentRoutineName());
    }

    public void executeName(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruRoutineManager sm = task.session().routineManager();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Current routine: %s", sm.getCurrentRoutineName()));
    }

    @Override
    public List<NArgCandidate> resolveCandidates(
            NCmdLine cmdLine,
            NCmdLineAutoCompleteResolver.Pos pos,
            NaruSession session) {
        List<NArgCandidate> candidates = new java.util.ArrayList<>();
        String[] stringArray = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String currentArg = wordIndex < stringArray.length ? stringArray[wordIndex] : "";

        if (wordIndex == 1) {
            addCandidates(candidates, currentArg, "list", "drop", "clear", "load", "unload", "run", "name", "help");
        }
        return candidates;
    }
}
