package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.nuts.text.NMsg;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ToolsDirective extends AbstractDirective {

    public ToolsDirective() {
        super("tools","context", "show tools","ctx");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruAgent r = context.session().agent();
        List<Map.Entry<String, NaruTool>> collected = context.session().registry().tools().entrySet().stream().sorted(Comparator.comparing(a -> a.getKey())).collect(Collectors.toList());
        r.log(NaruLogMode.AGENT_RESPONSE,NMsg.ofC("%s available tools:",collected.size()));
        for (Map.Entry<String, NaruTool> e : collected) {
            r.log(NaruLogMode.AGENT_RESPONSE,NMsg.ofC("  %s - %s",
                    NMsg.ofStyledPrimary1(e.getKey())
                    , e.getValue().getDescription(context.session())));
        }
    }
}
