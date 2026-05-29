package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.nuts.net.NWebCli;
import net.thevpc.nuts.net.NWebResponse;
import net.thevpc.nuts.time.NDuration;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
                NaruToolParameter.string("query", "query to look for", true)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String query = context.stringArg("query").onBlankEmpty().orNull();
        if (query == null) {
            return "Error: query is required";
        }

        String r = "searching for " + query;

        NWebCli http = NWebCli.of()
                .connectTimeout(NDuration.ofSeconds(30));
        NWebResponse response = http.GET("https://html.duckduckgo.com/html/")
                .parameter("q", query)
                .header("User-Agent", "Mozilla/5.0")
                .run();
        String contentAsString = response.contentAsString();
        String contentAsString2 = contentAsString.replaceAll("<[^>]*>", "").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#x27;", "'");
        List<String> sb = new ArrayList<>();
        int maxLines = 300;
        boolean stripped = false;
        for (String s : contentAsString2.split("\n")) {
            s = s.trim();
            if (!s.isEmpty()) {
                if (stripped) {
                    sb.add(s);
                } else {
                    if (sb.size() > 3 && s.equals("Past Year") && sb.get(sb.size() - 1).equals("Past Month") && sb.get(sb.size() - 2).equals("Past Week")) {
                        sb.clear();
                        stripped = true;
                    } else {
                        sb.add(s);
                    }
                }
            }
        }
        return sb.stream().limit(maxLines).collect(Collectors.joining("\n"));
    }
}
