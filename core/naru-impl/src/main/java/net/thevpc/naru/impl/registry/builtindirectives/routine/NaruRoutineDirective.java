package net.thevpc.naru.impl.registry.builtindirectives.routine;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.routine.RoutineHelper;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NRef;
import net.thevpc.nuts.util.NStringUtils;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class NaruRoutineDirective extends AbstractDirective {
    public NaruRoutineDirective() {
        super("routine", "routine", "create, update , list and run  routines", "routines");
        noCommand("list");
        register(new AbstractSubCommand("list", NText.ofPlain("list routines"),
                new SubCommandHelp("", "list routines")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<NaruResourceInfo> naruResourceInfos = task.session().routines();
                naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationInstant(), Comparator.reverseOrder()));
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
        });
        register(new AbstractSubCommand("show", NText.ofPlain("show current routine lines"),
                new SubCommandHelp("[<n1>-<n2>]", "show current routine lines.\nwhen filter is provided, only selected files are shown.\nex:" +
                        "\n /routine show -2..-1" +
                        "\n show last two lines" +
                        "\n /routine show 1-2" +
                        "\n show first two lines" +
                        "\n /routine show 1-2,4,-1" +
                        "\n show first two lines, 4th line and last line"

                )
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();

                NaruRoutine currentRoutine = task.editRoutine().get();

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
                for (Map.Entry<Integer, String> e : currentRoutine.getLinesSet().entrySet()) last = e;
                Integer lastKey = last == null ? 0 : last.getKey();
                if (toShow.isEmpty()) {
                    if (!currentRoutine.getLinesSet().isEmpty()) {
                        for (Map.Entry<Integer, String> e : currentRoutine.getLinesSet().entrySet()) {
                            logRow(e.getKey(), lastKey, e.getValue(), task);
                        }
                    }
                } else {
                    List<Integer> bb = toShow.stream().sorted().collect(Collectors.toList());
                    for (int k = 0; k < bb.size(); k++) {
                        int i = bb.get(k);
                        String s = currentRoutine.getLinesSet().get(i);
                        if (s != null) {
                            logRow(i, lastKey, s, task);
                        }
                    }
                }
            }

        });
        register(new AbstractSubCommand("clear", NText.ofPlain("clear current routine lines")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruRoutine cs = task.editRoutine().get();
                int count = cs.clear();
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s lines", count));
            }
        });

        register(new AbstractSubCommand("delete", NText.ofPlain("delete current routine lines"),
                new SubCommandHelp("<n1>-<n2>", "filter routine content lines to delete")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                if (cmdLine.isEmpty()) {
                    return;
                }
                NaruTask task = context.task();
                NaruRoutine cs = task.editRoutine().get();

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
        });


        register(new AbstractSubCommand("use", NText.ofPlain("select current routine by name"),
                new SubCommandHelp("<name>", "routine name (or path) to load")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String a = NStringUtils.trimToNull(cmdLine.next().map(NArg::image).orNull());
                if ("self".equalsIgnoreCase(a)) {
                    a = null;
                }
                String n = task.useRoutine(a).map(x -> x.name()).orNull();
                if (n != null) {
                    context.task().log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s] Use routine : %s", task.id(), n));
                }
            }
        });
        register(new AbstractSubCommand("self", NText.ofPlain("select <self> routine")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                task.useRoutine(null);
                context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("Unloaded routine context. Back to self routine."));
            }
        });
        register(new AbstractSubCommand("current", NText.ofPlain("shows current routine name")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Current routine: %s", task.editRoutineName()));
            }
        });
        register(new AbstractSubCommand("renum", NText.ofPlain("re-index routine lines"),
                new SubCommandHelp("[<routine>] [--start=<n>] [--inc=<n>] ",
                        "re-index routine <routine> (or current)"
                                + "\n--inc    :  define inter lines index gap (defaults to 10)"
                                + "\n--start  :  define first index (defaults to inc, aka 10)"
                )
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NRef<Integer> start = NRef.of(0);
                NRef<Integer> increment = NRef.of(0);
                NRef<NaruRoutine> r = NRef.of(task.editRoutine().get());
                cmdLine.matcher()
                        .with("--start").matchEntry(a -> start.set(a.intValue()))
                        .with("--inc").matchEntry(a -> increment.set(a.intValue()))
                        .with("--routine").matchEntry(a -> r.set(context.task().session().routine(a.stringValue(), context.task(), true).get()))
                        .withNonOption().matchAny(a -> r.set(context.task().session().routine(a.image(), context.task(), true).get()))
                        .requireAll();
                RoutineHelper.renum(start.get(), increment.get(), r.get());
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Renum routine: %s", r.get().name()));
            }
        });
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


}
