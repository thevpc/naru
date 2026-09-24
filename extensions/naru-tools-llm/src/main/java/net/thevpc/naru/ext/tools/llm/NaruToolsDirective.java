package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.*;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NStringUtils;

import java.util.*;
import java.util.stream.Collectors;

public class NaruToolsDirective extends NaruDirectiveBase {

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
                    List<NaruToolParameter> params = new ArrayList<>();
                    NaruToolDefinition d = t.getDefinition(context.task());
                    if (d instanceof NaruToolDefinitionFunction) {
                        params = ((NaruToolDefinitionFunction) d).getParams();
                    }
                    Map<String, Object> args = new HashMap<>();
                    while (!cmdLine.isEmpty()) {
                        String k = cmdLine.peek().get().key();
                        NaruToolParameter p = params.stream().filter(x -> x.getName().equals(k)).findFirst().orElse(null);
                        if (p != null) {
                            NArg e = cmdLine.nextEntry().get();
                            String v = e.value();
                            Object ov = v;
                            NaruToolParameter.Type pt = p.getType();
                            if (pt == null) {
                                pt = NaruToolParameter.Type.STRING;
                            }
                            switch (pt) {
                                case STRING: {
                                    ov = v;
                                    break;
                                }
                                case BOOLEAN: {
                                    ov = NLiteral.ofBoolean(v).orNull();
                                    break;
                                }
                                case NUMBER: {
                                    ov = NLiteral.ofNumber(v).orNull();
                                    break;
                                }
                                case INTEGER: {
                                    ov = NLiteral.ofInt(v).orNull();
                                    break;
                                }
                                case ARRAY: {
                                    if (v.startsWith("[") && v.endsWith("]")) {
                                        ov = NElementReader.ofTson().read(v).asArray().get().children().stream().map(x -> NElement.simpleOf(x)).toArray();
                                    } else {
                                        ov = NStringUtils.split(v, ",::", true, true).toArray();
                                    }
                                    break;
                                }
                                case OBJECT: {
                                    if (v.startsWith("{") && v.endsWith("}")) {
                                        ov = NElement.simpleOf(NElementReader.ofTson().read(v).asObject().get());
                                    } else {
                                        ov = v;
                                    }
                                    break;
                                }
                            }
                            args.put(p.getName(), ov);
                        } else {
                            cmdLine.skip();
                        }
                    }
                    String result = t.execute(new NaruToolCallContextImpl(
                            args, context.task()
                    ));
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                    context.task().addHistory(NaruMessage.user(NMsg.ofC("calls tool %s %s\nresults:\n%s", a.image(), args, result).toString()));
                    // Publish the exit code of process-running tools (e.g. run_shell)
                    // so script control-flow (/while, /if) can condition on it, e.g.
                    // "run until 'mvn -o -q test' exits 0". Results without an
                    // EXIT_CODE= prefix leave the previous value untouched.
                    Integer exitCode = extractExitCode(result);
                    if (exitCode != null) {
                        context.task().setTaskEnv("lastExitCode", exitCode);
                    }
                }
            }
        });
        register(new AbstractSubCommand("describe", NText.ofPlain("describe a tool"),
                new SubCommandHelp("<tool-name>", "describe a tool by name")
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
                    List<NaruToolParameter> params = new ArrayList<>();
                    NaruToolDefinition d = t.getDefinition(context.task());
                    if (d instanceof NaruToolDefinitionFunction) {
                        params = ((NaruToolDefinitionFunction) d).getParams();
                    }
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s - %s",
                            NMsg.ofStyledPrimary1(t.name())
                            , t.getDescription(context.task())));
                    for (NaruToolParameter param : params) {
                        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s - %s",
                                NMsg.ofStyledPrimary1(param.getName())
                                , param.getDescription()));
                    }
                }
            }
        });
    }

    /**
     * Extract the numeric exit code from a tool result formatted as
     * "EXIT_CODE=&lt;code&gt;\n…" (the run_shell contract). Returns {@code null}
     * for results carrying no exit code, so callers can keep the previous value.
     */
    private static Integer extractExitCode(String result) {
        if (result != null && result.startsWith("EXIT_CODE=")) {
            String v = result.substring("EXIT_CODE=".length());
            int nl = v.indexOf('\n');
            if (nl >= 0) {
                v = v.substring(0, nl);
            }
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignore) {
                // not a parsable exit code: treat as "no exit code published"
            }
        }
        return null;
    }

}
