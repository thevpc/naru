package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.impl.util.ToolHelper;

public class SearchWebScriptTool implements NaruTool {

    @Override
    public String name() {
        return "search_web";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "search the web for the provided query";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(session),
                NaruToolParameter.string("query", "query to look for", true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String query = context.stringArg("query").onBlankEmpty().orNull();
        return ToolHelper.searchWeb(context.task(),query);
    }
}
