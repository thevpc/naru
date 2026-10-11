package net.thevpc.naru.ext.tools.web;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;

public class WebFetchTool extends DefaultNaruTool {

    public WebFetchTool() {
        super("web_fetch", new String[]{NaruToolTags.NETWORK});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Fetches content from a specified URL and converts it to requested format (markdown, text, or html). Defaults to markdown.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("url", "The URL to fetch content from", true).build(),
                NaruToolParameter.string("format", "The format to return the content in (markdown, text, or html). Defaults to markdown.", false, "markdown").build(),
                NaruToolParameter.integer("timeout", "Optional timeout in seconds (max 120)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String url = context.stringArg("url").orNull();
        String format = context.stringArg("format").orElse("markdown");
        Integer timeout = context.numberArg("timeout").map(Number::intValue).orNull();
        return WebToolHelper.webFetch(context.task(), url, format, timeout);
    }
}
