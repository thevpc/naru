package net.thevpc.naru.ext.tools.web;

import net.thevpc.naru.api.access.NaruAccessDecision;
import net.thevpc.naru.api.access.NaruAccessGuard;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WebToolHelper {

    /** The DuckDuckGo endpoint {@link #searchWeb} talks to, named so the guard can name it. */
    public static final String SEARCH_URL = "https://html.duckduckgo.com/html/";

    public static String searchWeb(NaruTask task, String query) {
        if (query == null) {
            return "Error: query is required";
        }
        String denied = deny(task, SEARCH_URL);
        if (denied != null) {
            return denied;
        }

        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(30));
        NHttpResponse response = http.GET(SEARCH_URL)
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

    public static String webFetch(NaruTask task, String urlStr, String format, Integer timeoutSec) {
        if (NBlankable.isBlank(urlStr)) {
            return "Error: url is required";
        }
        String denied = deny(task, urlStr);
        if (denied != null) {
            return denied;
        }
        String fmt = (format == null || format.isBlank()) ? "markdown" : format.toLowerCase().trim();
        int tSec = (timeoutSec == null || timeoutSec <= 0) ? 30 : Math.min(timeoutSec, 120);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(tSec))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(tSec))
                    .header("User-Agent", "Mozilla/5.0 (compatible; naru/1.1)")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                return String.format("StatusCode: non 2xx status code (%d GET %s)", status, urlStr);
            }

            String body = response.body();
            if (body == null) {
                body = "";
            }

            switch (fmt) {
                case "html":
                    return body;
                case "text":
                    return htmlToText(body);
                case "markdown":
                default:
                    return htmlToMarkdown(body);
            }

        } catch (Exception e) {
            return "Error fetching " + urlStr + ": " + e.getMessage();
        }
    }

    /**
     * The web domain's single call into the session access policy (design hooks
     * #7/#8). Both the fetch and the search back end ask before any socket is
     * opened, so a denied URL never leaves the process.
     */
    private static String deny(NaruTask task, String url) {
        NaruResource resource = NaruResource.url(url);
        NaruAccessDecision decision = NaruAccessGuard.check(task, resource, NaruAction.FETCH);
        return decision.allowed() ? null : NaruAccessGuard.denyMessage(task, resource, NaruAction.FETCH, decision);
    }

    public static String htmlToText(String html) {
        if (html == null) return "";
        // Remove script and style tags
        String h = html.replaceAll("(?is)<script\\b[^<]*(?:(?!</script>)<[^<]*)*</script>", "");
        h = h.replaceAll("(?is)<style\\b[^<]*(?:(?!</style>)<[^<]*)*</style>", "");
        // Strip tags
        return h.replaceAll("<[^>]+>", "");
    }

    public static String htmlToMarkdown(String html) {
        if (html == null) return "";

        String title = "";
        Matcher mTitle = Pattern.compile("(?is)<title>(.*?)</title>").matcher(html);
        if (mTitle.find()) {
            title = mTitle.group(1).trim();
        }

        // Drop script and style
        String h = html.replaceAll("(?is)<script\\b[^<]*(?:(?!</script>)<[^<]*)*</script>", "");
        h = h.replaceAll("(?is)<style\\b[^<]*(?:(?!</style>)<[^<]*)*</style>", "");

        // Extract body if present
        Matcher mBody = Pattern.compile("(?is)<body[^>]*>(.*?)</body>").matcher(h);
        String body = mBody.find() ? mBody.group(1) : h;

        // Convert headings
        body = body.replaceAll("(?is)<h1[^>]*>(.*?)</h1>", "# $1\n\n");
        body = body.replaceAll("(?is)<h2[^>]*>(.*?)</h2>", "## $1\n\n");
        body = body.replaceAll("(?is)<h3[^>]*>(.*?)</h3>", "### $1\n\n");

        // Convert links <a href="...">text</a> -> [text](href)
        body = body.replaceAll("(?is)<a\\s+[^>]*href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", "[$2]($1)");

        // Convert paragraphs
        body = body.replaceAll("(?is)<p[^>]*>(.*?)</p>", "$1\n\n");

        // Convert list items
        body = body.replaceAll("(?is)<li>(.*?)</li>", "-   $1\n");
        body = body.replaceAll("(?is)</?[uo]l[^>]*>", "\n");

        // Convert pre
        body = body.replaceAll("(?is)<pre[^>]*>(.*?)</pre>", "$1\n\n");

        // Strip any remaining tags
        body = body.replaceAll("<[^>]+>", "");

        // Format into blocks
        List<String> parts = new ArrayList<>();
        if (!title.isEmpty()) {
            parts.add(title);
        }
        for (String block : body.split("\n\n")) {
            String trimmed = block.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }

        return String.join("\n\n", parts);
    }
}
