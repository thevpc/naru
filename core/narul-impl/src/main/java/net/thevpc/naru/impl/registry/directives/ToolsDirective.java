package net.thevpc.naru.impl.registry.directives;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.impl.registry.NaruToolRegistryImpl;
import net.thevpc.nuts.text.NMsg;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

public class ToolsDirective extends AbstractDirective {
    private final NaruToolRegistryImpl naruToolRegistry;

    public ToolsDirective(NaruToolRegistryImpl naruToolRegistry) {
        super("tools", "show tools");
        this.naruToolRegistry = naruToolRegistry;
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruAgent r = context.session().runner();
        r.log(NaruLogMode.AGENT_RESPONSE,NMsg.ofC("Available tools:"));
        for (Map.Entry<String, NaruTool> e : naruToolRegistry.tools().entrySet().stream().sorted(Comparator.comparing(a -> a.getKey())).collect(Collectors.toList())) {
            r.log(NaruLogMode.AGENT_RESPONSE,NMsg.ofC("  %s - %s",
                    NMsg.ofStyledPrimary1(e.getKey())
                    , e.getValue().getDescription()));
        }
    }
}
