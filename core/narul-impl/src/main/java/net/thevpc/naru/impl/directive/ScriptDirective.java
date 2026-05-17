package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NStringUtils;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class ScriptDirective extends AbstractDirective {
    public ScriptDirective() {
        super("script", "create, update , list and run  scripts");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeList(context, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
                case "name": {
                    executeName(context, cmdLine);
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
                case "load": {
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
                    sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command %s", a.image()));
                }
            }
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();

        NaruRoutineManager sm = sessionContext.routineManager();
        NaruRoutine cs = sm.getCurrentRoutine();

        Set<Integer> toShow = new HashSet<>();
        int historySize = sessionContext.history().size();
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
                        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid position to drop", range).asError());
                        return;
                    }
                }
            }
        }
        Map.Entry<Integer, String> last = null;
        for (Map.Entry<Integer, String> e : cs.getLines().entrySet()) last = e;
        Integer lastKey = last == null ? 0 : last.getKey();
        if (toShow.isEmpty()) {
            if (!cs.getLines().isEmpty()) {
                for (Map.Entry<Integer, String> e : cs.getLines().entrySet()) {
                    logRow(e.getKey(), lastKey, e.getValue(), sessionContext);
                }
            }
        } else {
            List<Integer> bb = toShow.stream().sorted().collect(Collectors.toList());
            for (int k = 0; k < bb.size(); k++) {
                int i = bb.get(k);
                String s = cs.getLines().get(i);
                if (s != null) {
                    logRow(i, lastKey, s, sessionContext);
                }
            }
        }
    }

    private void logRow(int rowIndex, int max, String a, NaruSession sessionContext) {
        int zeros = (int) Math.ceil(Math.log10(max));
        if (zeros <= 0) {
            zeros = 1;
        }
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));

        List<String> lines = NStringUtils.splitLines(a);
        for (int j = 0; j < lines.size(); j++) {
            String line = lines.get(j);
            if (j == 0) {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s",
                        NMsg.ofStyledNumber(zformat.format(rowIndex)),
                        line));
            } else {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        %s", line));
            }
        }
    }

    public void executeClear(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NaruRoutineManager sm = sessionContext.routineManager();
        NaruRoutine cs = sm.getCurrentRoutine();
        int count = cs.clear();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s lines", count));
    }

    public void executeDrop(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        if (cmdLine.isEmpty()) {
            return;
        }
        NaruSession sessionContext = context.session();
        NaruRoutineManager sm = sessionContext.routineManager();
        NaruRoutine cs = sm.getCurrentRoutine();

        Set<Integer> toRemove = new HashSet<>();
        int historySize = sessionContext.history().size();
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
                        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid position to drop", range).asError());
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
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s lines", b.size()));
    }

    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("script"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, kk);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s list", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s list <n1>-<p1>,<n2>-<p2>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list intervals"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s drop <n1>-<p1>,<n2>-<p2>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           drop intervals"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s clear", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           clear script"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s load <name>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           load script (or default 'main')"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s unload", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           unload script (back to 'main')"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s run", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           run script"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s name", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show script name"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }

    public void executeLoad(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NaruRoutineManager sm = sessionContext.routineManager();
        String name = context.argument();
        if (name.isEmpty()) {
            name = "main";
        }
        sm.switchRoutine(name);
        context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded script context: %s", name));
    }

    public void executeUnload(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NaruRoutineManager sm = sessionContext.routineManager();
        sm.switchRoutine("main");
        context.session().log(NaruLogMode.PROGRESS, NMsg.ofC("Unloaded script context. Back to main."));
    }

    public void executeRun(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NaruRoutineManager sm = sessionContext.routineManager();
        sessionContext.runner().invokeScript(sessionContext, sm.getCurrentRoutineName());
    }

    public void executeName(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NaruRoutineManager sm = sessionContext.routineManager();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Current script: %s", sm.getCurrentRoutineName()));
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
