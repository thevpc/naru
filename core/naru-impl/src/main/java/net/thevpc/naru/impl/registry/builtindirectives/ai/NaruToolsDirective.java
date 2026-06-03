package net.thevpc.naru.impl.registry.builtindirectives.ai;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.impl.registry.NaruToolCallContextImpl;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NOptional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class NaruToolsDirective extends AbstractDirective {

    public NaruToolsDirective() {
        super("tools", "ai", "manage AI tools", "tool");
        register(new AbstractSubCommand("list", NText.ofPlain("list available tools")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruAgent r = context.task().session().agent();
                List<Map.Entry<String, NaruTool>> collected = context.task().session().registry().tools().entrySet().stream().sorted(Comparator.comparing(a -> a.getKey())).collect(Collectors.toList());
                r.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s available directives:", collected.size()));
                for (Map.Entry<String, NaruTool> e : collected) {
                    r.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s - %s",
                            NMsg.ofStyledPrimary1(e.getKey())
                            , e.getValue().getDescription(context.task().session())));
                }
            }
        });
        register(new AbstractSubCommand("run", NText.ofPlain("run a tool"),
                new SubCommandHelp("<tool-name>  [<key>=<value>...]", "run a tool by name with named arguments\nex:\n/tools run file_read path=src/Main.java")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruAgent r = context.task().session().agent();
                NCmdLine cmdline = NCmdLine.of(context.argument());
                NArg a = cmdline.next().orNull();
                if (a == null) {
                    r.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing tool"));
                } else {
                    NaruTool t = context.task().session().registry().findTool(a.image()).orNull();
                    if (t == null) {
                        r.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing tool : %s", a.image()));
                        return;
                    }
                    Map<String, Object> args = new HashMap<>();
                    while (!cmdline.isEmpty()) {
                        NArg e = cmdline.nextEntry().get();
                        args.put(e.key(), e.value());
                    }
                    String result = t.execute(new NaruToolCallContextImpl(
                            args, context.task()
                    ));
                    r.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                    context.task().addHistory(NaruMessage.user(NMsg.ofC("calls tool %s %s\nresults:\n%s", a.image(), args, result).toString()));
                }
            }
        });
    }

}
