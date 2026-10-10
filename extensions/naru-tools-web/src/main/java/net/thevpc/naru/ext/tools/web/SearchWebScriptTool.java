package net.thevpc.naru.ext.tools.web;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.nuts.text.NText;

public class SearchWebScriptTool extends DefaultNaruTool {

    public SearchWebScriptTool() {
        super("search_web", new String[]{NaruToolTags.NETWORK});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("search the web for the provided query");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("query", "query to look for", true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String query = context.stringArg("query").onBlankEmpty().orNull();
        return WebToolHelper.searchWeb(context.task(),query);
    }
}
