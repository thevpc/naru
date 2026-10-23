package net.thevpc.naru.ext.tools.web;

import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.io.*;
import net.thevpc.nuts.net.*;
import net.thevpc.nuts.util.*;
import net.thevpc.naru.api.task.*;
import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class WebSearchBuilder {
    private String query;
    private String apiKey;
    private String searchEngineId;
    private WebSearchEngine engine;
    private int maxResults = 10;
    private boolean cleanHtml = true;
    private NDuration timeout = NDuration.ofSeconds(30);

    private WebSearchBuilder() {
    }

    public static WebSearchBuilder create() {
        return new WebSearchBuilder();
    }

    public WebSearchBuilder query(String query) {
        this.query = query;
        return this;
    }

    public WebSearchBuilder googleCredentials(String apiKey, String searchEngineId) {
        this.apiKey = apiKey;
        this.searchEngineId = searchEngineId;
        this.engine = WebSearchEngine.GOOGLE;
        return this;
    }

    public WebSearchBuilder maxResults(int maxResults) {
        this.maxResults = maxResults;
        return this;
    }

    public WebSearchBuilder cleanHtml(boolean cleanHtml) {
        this.cleanHtml = cleanHtml;
        return this;
    }

    public WebSearchBuilder timeout(NDuration timeout) {
        this.timeout = timeout;
        return this;
    }

    public String execute(NaruTask task) {
        if (query == null) {
            return "Error: query is required";
        }
        if (engine == null) {
            return searchWithDuckDuckGo(task);
        }
        switch (engine) {
            case GOOGLE:
                return searchWithGoogle(task);
            case DUCKDUCKGO:
                return searchWithDuckDuckGo(task);
        }
        return "Error: query is required";
    }

    private String searchWithGoogle(NaruTask task) {
        if (query == null) {
            return "Error: query is required";
        }
        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(30));

        String cx = searchEngineId;
        String key = apiKey;
        if (NBlankable.isBlank(cx)) {
            cx = (String)task.getTaskEnv("websearch.google.cx", true).orNull();
        }
        if (NBlankable.isBlank(key)) {
            key = (String)task.getTaskEnv("websearch.google.apikey", true).orNull();
        }
        if (NBlankable.isBlank(cx) || NBlankable.isBlank(key)) {
//            return "Error: `websearch.google.cx` is required";
            NHttpResponse response = http.GET("https://www.google.com/search")
                    .header("User-Agent", userAgent())
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .parameter("q", query)
                    .parameter("num", "10")
                    .run();
            String json = response.contentAsString();
            return htmlToMarkdown(json);
        }

        NHttpResponse response = http.GET("https://www.googleapis.com/customsearch/v1")
                .parameter("key", apiKey)
                .parameter("cx", searchEngineId)
                .parameter("q", query)
                .parameter("num", "10")
                .run();

        String json = response.contentAsString();

        // Parse results using Jsoup if any HTML snippets need cleaning
        return parseGoogleSearchResults(json);
    }

    private String userAgent() {
        // "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    }

    private String parseGoogleSearchResults(String json) {
        return json;
    }

    private String searchWithDuckDuckGo(NaruTask task) {
        // Still use the API endpoint to avoid CAPTCHA
        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(30));

        NHttpResponse response = http.GET("https://api.duckduckgo.com/")
                .parameter("q", query)
                .parameter("format", "json")
                .parameter("no_html", "1")
                .header("User-Agent", userAgent())
                .run();

        String jsonContent = response.contentAsString();

        // Parse and extract meaningful text from JSON
        return extractTextFromDDGResponse(jsonContent);
    }
    private static String extractTextFromDDGResponse(String json) {
        // Since you prefer avoiding Jackson, here's a simple approach
        // You could also use a lightweight JSON parser

        StringBuilder result = new StringBuilder();

        // Extract AbstractText field (main answer)
        String abstractText = extractJsonField(json, "AbstractText");
        if (abstractText != null && !abstractText.isEmpty()) {
            result.append(abstractText).append("\n\n");
        }

        // Extract Heading
        String heading = extractJsonField(json, "Heading");
        if (heading != null && !heading.isEmpty()) {
            result.insert(0, heading + "\n");
        }

        // Extract RelatedTopics
        String relatedTopics = extractRelatedTopics(json);
        if (relatedTopics != null && !relatedTopics.isEmpty()) {
            result.append("\nRelated:\n").append(relatedTopics);
        }

        return result.toString().trim();
    }

    // Simple JSON field extractor (avoiding full JSON library)
    private static String extractJsonField(String json, String fieldName) {
        String pattern = "\"" + fieldName + "\":\"";
        int start = json.indexOf(pattern);
        if (start == -1) return null;

        start += pattern.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;

        String value = json.substring(start, end);
        // Unescape JSON strings
        return value.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\");
    }

    private static String extractRelatedTopics(String json) {
        // Simplified extraction - you might want a proper JSON parser for complex structures
        StringBuilder topics = new StringBuilder();
        int pos = 0;
        int count = 0;

        while ((pos = json.indexOf("\"FirstURL\"", pos)) != -1 && count < 5) {
            int urlStart = pos + 11; // length of "FirstURL":"
            int urlEnd = json.indexOf("\"", urlStart);
            if (urlEnd == -1) break;

            String url = json.substring(urlStart, urlEnd);

            // Find corresponding Text
            int textPos = json.indexOf("\"Text\":\"", urlEnd);
            if (textPos != -1) {
                int textStart = textPos + 8;
                int textEnd = json.indexOf("\"", textStart);
                if (textEnd != -1) {
                    String text = json.substring(textStart, textEnd)
                            .replace("\\\"", "\"")
                            .replace("\\n", " ");
                    topics.append("- ").append(text).append("\n  ").append(url).append("\n");
                    count++;
                }
            }
            pos = urlEnd + 1;
        }

        return topics.toString();
    }

    // Utility method to clean HTML to plain text using Jsoup
    public static String htmlToText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        return doc.text();
    }

    // Utility to convert HTML to markdown-like format
    public static String htmlToMarkdown(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Document doc = Jsoup.parse(html);

        StringBuilder md = new StringBuilder();

        // Convert links
        Elements links = doc.select("a[href]");
        for (Element link : links) {
            String text = link.text();
            String href = link.attr("href");
            link.replaceWith(new org.jsoup.nodes.TextNode("[" + text + "](" + href + ")"));
        }

        // Get cleaned text
        md.append(doc.body().text());

        return md.toString();
    }
}

/// / Usage:
//String result = WebSearchBuilder.create()
//        .query("antenna modeling techniques")
//        .googleCredentials(apiKey, cx)
//        .maxResults(10)
//        .cleanHtml(true)
//        .timeout(NDuration.ofSeconds(30))
//        .execute(task);
