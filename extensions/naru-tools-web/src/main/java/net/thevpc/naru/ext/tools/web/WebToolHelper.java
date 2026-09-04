package net.thevpc.naru.ext.tools.web;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.time.NDuration;

import java.util.*;
import java.util.stream.Collectors;

public class WebToolHelper {

    public static String searchWeb(NaruTask task, String query) {
        if (query == null) {
            return "Error: query is required";
        }

        String r = "searching for " + query;

        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(30));
        NHttpResponse response = http.GET("https://html.duckduckgo.com/html/")
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
