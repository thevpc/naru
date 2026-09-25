package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;

import java.util.*;

public class NaruModelRequest {
    private List<NaruMessage> messages;
    private List<NaruToolDefinition> tools;
    private Map<String, NElement> env;
    /**
     * Optional stable/volatile decomposition of this same request. Null for
     * every caller that does not opt in, in which case {@link #messages()} and
     * {@link #tools()} remain the whole truth and providers behave exactly as
     * before.
     *
     * <p>It is carried on the request rather than in a separate argument purely
     * so the long-standing {@code chat(NaruModelRequest, NaruTask)} signature
     * keeps working: caching support is additive, not a new calling convention.
     */
    private NaruCacheableContext cacheableContext;

    public NaruModelRequest(List<NaruMessage> messages, List<NaruToolDefinition> tools, Map<String, NElement> env) {
        this.messages = messages;
        this.tools = tools;
        this.env = env;
    }

    public NaruModelRequest(List<NaruMessage> messages, Map<String, NElement> env) {
        this.messages = messages;
        this.tools = Collections.emptyList();
        this.env = env;
    }

    public Map<String, NElement> env() {
        return env;
    }

    public List<NaruMessage> messages() {
        return messages;
    }

    public List<NaruToolDefinition> tools() {
        return tools;
    }

    public NaruCacheableContext cacheableContext() {
        return cacheableContext;
    }

    public boolean hasCacheableContext() {
        return cacheableContext != null;
    }

    /**
     * Attach a segmentation. The caller is responsible for keeping it
     * consistent with {@code messages}/{@code tools}; when both are present
     * providers that understand caching use the segmentation, and everyone else
     * keeps reading the flat fields.
     */
    public NaruModelRequest withCacheableContext(NaruCacheableContext cacheableContext) {
        NaruModelRequest r = new NaruModelRequest(messages, tools, env);
        r.cacheableContext = cacheableContext;
        return r;
    }

    public NaruModelRequest withMessages(List<NaruMessage> finalMessages) {
        NaruModelRequest r = new NaruModelRequest(finalMessages,
                new ArrayList<>(tools)
                ,new HashMap<>(env)
        );
        r.cacheableContext = cacheableContext;
        return r;
    }
}
