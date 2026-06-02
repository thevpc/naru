package net.thevpc.naru.impl.registry.builtindirectives.ai;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.text.NMsg;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class NaruToolsDirective extends AbstractDirective {

    public NaruToolsDirective() {
        super("tools","ai", "manage AI tools","tool");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruAgent r = context.task().session().agent();
        List<Map.Entry<String, NaruTool>> collected = context.task().session().registry().tools().entrySet().stream().sorted(Comparator.comparing(a -> a.getKey())).collect(Collectors.toList());
        r.log(NaruLogMode.AGENT_RESPONSE,NMsg.ofC("%s available directives:",collected.size()));
        for (Map.Entry<String, NaruTool> e : collected) {
            r.log(NaruLogMode.AGENT_RESPONSE,NMsg.ofC("  %s - %s",
                    NMsg.ofStyledPrimary1(e.getKey())
                    , e.getValue().getDescription(context.task().session())));
        }
    }
}
