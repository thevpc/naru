package net.thevpc.naru.impl.registry.builtindirectives.ai;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.impl.registry.NaruToolCallContextImpl;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;

import java.util.*;
import java.util.stream.Collectors;

public class NaruToolsDirective extends AbstractDirective {

    public NaruToolsDirective() {
        super("tools", "ai", "manage AI tools", "tool");
        this.noCommand("list");
        register(new AbstractSubCommand("all", NText.ofPlain("list all tools")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                List<Map.Entry<String, NaruTool>> collected = context.task().session().registry().tools().entrySet().stream().sorted(Comparator.comparing(a -> a.getKey())).collect(Collectors.toList());
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s available tools:", collected.size()));
                for (Map.Entry<String, NaruTool> e : collected) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s - %s",
                            NMsg.ofStyledPrimary1(e.getKey())
                            , e.getValue().getDescription(context.task())));
                }
            }
        });
        register(new AbstractSubCommand("list", NText.ofPlain("list selected tools")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                List<NaruToolDefinition> collected = context.task().findTools().stream().sorted(Comparator.comparing(a -> a.getName())).collect(Collectors.toList());
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s selected tools:", collected.size()));
                for (NaruToolDefinition e : collected) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s - %s",
                            NMsg.ofStyledPrimary1(e.getName())
                            , e.getDescription()));
                }
            }
        });
        register(new AbstractSubCommand("unselected", NText.ofPlain("list unselected (non included) tools")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                Map<String, NaruToolDefinition> collected = context.task().findTools().stream()
                        .collect(Collectors.toMap(x -> x.getName(), x -> x));
                Map<String, NaruTool> allTools = context.task().session().registry().tools();
                for (String s : allTools.keySet()) {
                    allTools.remove(s);
                }
                List<Map.Entry<String, NaruTool>> all = allTools.entrySet().stream().sorted(Comparator.comparing(a -> a.getKey())).collect(Collectors.toList());

                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s unselected tools:", collected.size()));
                for (Map.Entry<String, NaruTool> e : all) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s - %s",
                            NMsg.ofStyledPrimary1(e.getValue().name())
                            , e.getValue().getDescription(context.task())));
                }
            }
        });
        register(new AbstractSubCommand("excluded", NText.ofPlain("list excluded tools")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                List<String> collected = context.task().findToolExclusions().stream().sorted().collect(Collectors.toList());
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s excluded tools:", collected.size()));
                for (String e : collected) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s",
                            NMsg.ofStyledPrimary1(e)));
                }
            }
        });
        register(new AbstractSubCommand("exclude", NText.ofPlain("add tool to exclusion set"),
                new SubCommandHelp("<tool-name>... [<tool-name>...]", "add tool to exclusion set")
                ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NCmdLine cmd = NCmdLine.of(context.argument());
                if (cmd.isEmpty()) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing tool"));
                    return;
                }
                while (!cmd.isEmpty()) {
                    NArg a = cmd.next().get();
                    context.task().addToolExclusion(a.image());
                }
            }
        });
        register(new AbstractSubCommand("unexclude", NText.ofPlain("remove tool from exclusion set"),
                new SubCommandHelp("<tool-name>... [<tool-name>...]", "remove tool from exclusion set")
                ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NCmdLine cmd = NCmdLine.of(context.argument());
                if (cmd.isEmpty()) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing tool"));
                    return;
                }
                while (!cmd.isEmpty()) {
                    NArg a = cmd.next().get();
                    context.task().removeToolExclusion(a.image());
                }
            }
        });

        register(new AbstractSubCommand("tags", NText.ofPlain("list available tags")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                Map<String, NaruToolTag> tags = context.task().session().registry().availableTags();
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s available tags:", tags.size()));
                for (Map.Entry<String, NaruToolTag> e : tags.entrySet()) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s - %s",
                            NMsg.ofStyledPrimary1(e.getKey())
                            , e.getValue().description()));
                }
            }
        });

        register(new AbstractSubCommand("add-tagged", NText.ofPlain("add tools with the given tags"),
                new SubCommandHelp("<tag-name>... [<tag-name>...]", "add tools with the given tags")
                ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                Set<String> tags = new LinkedHashSet<>();
                while (!cmdLine.isEmpty()) {
                    NArg a = cmdLine.next().get();
                    tags.add(a.image());
                }
                for (String tag : tags) {
                    context.task().addToolTag(tag);
                }
            }
        });
        register(new AbstractSubCommand("remove-tagged", NText.ofPlain("remove tools with the given tags"),
                new SubCommandHelp("<tag-name>... [<tag-name>...]", "remove tools with the given tags")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                Set<String> tags = new LinkedHashSet<>();
                while (!cmdLine.isEmpty()) {
                    NArg a = cmdLine.next().get();
                    tags.add(a.image());
                }
                for (String tag : tags) {
                    context.task().removeToolTag(tag);
                }
            }
        });
        register(new AbstractSubCommand("run", NText.ofPlain("run a tool"),
                new SubCommandHelp("<tool-name>  [<key>=<value>...]", "run a tool by name with named arguments\nex:\n/tools run file_read path=src/Main.java")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NArg a = cmdLine.next().orNull();
                if (a == null) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing tool"));
                } else {
                    NaruTool t = context.task().session().registry().findTool(a.image()).orNull();
                    if (t == null) {
                        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing tool : %s", a.image()));
                        return;
                    }
                    Map<String, Object> args = new HashMap<>();
                    while (!cmdLine.isEmpty()) {
                        NArg e = cmdLine.nextEntry().get();
                        args.put(e.key(), e.value());
                    }
                    String result = t.execute(new NaruToolCallContextImpl(
                            args, context.task()
                    ));
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                    context.task().addHistory(NaruMessage.user(NMsg.ofC("calls tool %s %s\nresults:\n%s", a.image(), args, result).toString()));
                }
            }
        });
    }

}
