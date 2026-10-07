package net.thevpc.naru.ext.models.openai;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.anthropic.NaruModelProtocolAnthropicCompat;
import net.thevpc.naru.ext.models.gemini.NaruModelProtocolGeminiNative;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.log.NLog;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpCode;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Base class for OpenAI-compatible cloud providers (Groq, Cerebras, OpenRouter,
 * GitHub Models, ...). Subclasses provide a name, a default base url, a model
 * list and static capabilities; the wire protocol and auth handling are
 * inherited.
 *
 * <p>The wire shape is protocol-aware (design doc §8): an instance resolves its
 * protocol id — its own {@code --protocol} param, else the type's
 * {@link #defaultProtocol()} — and the chat wire, base url, model-enumeration
 * path/parse and default caching mode are those of the matching wire class
 * ({@link NaruModelProtocolOpenAICompat}, {@link NaruModelProtocolAnthropicCompat}
 * or {@link NaruModelProtocolGeminiNative}). Providers whose shape is fixed
 * simply declare no other {@code --protocol}.
 */
public abstract class AbstractOpenAICompatProvider extends AbstractNaruModelProvider {
    /**
     * Live model enumeration cached per (type, protocol, url, key) — design §9:
     * several registrations of one type that share a key and endpoint cost one
     * listing, not one each, while a registration with its own key gets its own
     * entry, as does one speaking a different wire protocol.
     */
    private static final Map<String, ModelCacheEntry> LIVE_MODELS_CACHE = new ConcurrentHashMap<>();
    private static final long LIVE_MODELS_TTL_MS = 5 * 60 * 1000L;
    private static final long REACHABILITY_TTL_MS = 5000L;

    /**
     * Reachability results keyed by url: registrations pointing at the same
     * endpoint share one probe (design §11: probe caching is type/url scoped,
     * not instance scoped).
     */
    private static final Map<String, ProbeResult> REACHABILITY_CACHE = new ConcurrentHashMap<>();

    protected final Map<NaruModelConfig, NaruModelProtocol> protocols = new HashMap<>();

    protected AbstractOpenAICompatProvider(String name, String[] defaultEnvKeys) {
        super(name, defaultEnvKeys);
    }

    protected String chatPath() {
        return "chat/completions";
    }

    /**
     * Fallback base url when {@code <name>.url} config is not set. This is the
     * provider's own endpoint and belongs to its {@link #defaultProtocol()} wire;
     * protocol-aware resolution is {@link #resolvedBaseUrl(NaruSession, String)}.
     */
    protected abstract String baseUrl(NaruSession session);

    /**
     * Statically maps capabilities since cloud-hosted capabilities cannot be polled
     * natively. Capabilities may depend on the wire shape (native Gemini's
     * {@code CachedContent}-resource caching, ...) — when they do, resolve the wire
     * via {@link #resolvedProtocolId(NaruSession)} rather than taking it as a
     * parameter; providers that do not change under {@code --protocol} ignore it.
     */
    protected abstract NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session);

    /**
     * The wire decision (design doc §8). The provider types that build on this
     * base all resolve here: a configured {@code --protocol} that the provider
     * declares is built as that wire shape; anything else — nothing configured,
     * the provider's own default id, or a wire the provider does not declare —
     * falls back to the provider's own default protocol. A non-default protocol
     * the provider cannot speak is <b>ignored with a warning</b>, never silently
     * substituted and never fatal: the default wire keeps working.
     */
    protected NaruModelProtocol createProtocol(NaruModelConfig model, NaruModelCapabilities capabilities, NaruSession session) {
        String pid = resolvedProtocolId(session);
        if (pid == null || pid.equalsIgnoreCase(defaultProtocol())) {
            return createDefaultProtocol(model, capabilities, session);
        }
        if (!supportsProtocolId(pid)) {
            NLog.of(getClass()).log(NMsg.ofC(
                    "provider '%s' does not support protocol '%s' (supported: %s) — ignored, using its default wire '%s'.",
                    name(), pid, String.join(", ", new TreeSet<>(supportedProtocols())), defaultProtocol()
            ).asWarning());
            return createDefaultProtocol(model, capabilities, session);
        }
        return createProtocolShape(model, capabilities, session, pid);
    }

    /**
     * Builds a declared, non-default wire shape: {@link NaruModelProtocolGeminiNative
     * native gemini}, {@link NaruModelProtocolAnthropicCompat anthropic}, or the
     * plain {@link NaruModelProtocolOpenAICompat OpenAI-compatible} shape (the
     * wire id itself is not inspected here beyond the two distinct shapes — the
     * openai id and any provider-declared id that maps onto the openai wire).
     */
    protected NaruModelProtocol createProtocolShape(NaruModelConfig model, NaruModelCapabilities capabilities, NaruSession session, String protocolId) {
        if (NaruModelProtocolGeminiNative.PROTOCOL_ID.equalsIgnoreCase(protocolId)) {
            return new NaruModelProtocolGeminiNative(this, model, name(), null,
                    capabilities, resolvedBaseUrl(session, protocolId));
        }
        if (NaruModelProtocolAnthropicCompat.PROTOCOL_ID.equalsIgnoreCase(protocolId)) {
            return new NaruModelProtocolAnthropicCompat(this, model, name(), "v1/messages",
                    capabilities, resolvedBaseUrl(session, protocolId));
        }
        return new NaruModelProtocolOpenAICompat(this, model, name(), resolvedChatPath(session),
                capabilities, resolvedBaseUrl(session, protocolId));
    }

    /**
     * The chat endpoint path of the openai-shaped wire: the instance's own
     * {@code chatPath} param, else the provider's {@link #chatPath()} hook.
     */
    protected String resolvedChatPath(NaruSession session) {
        return configValue("chatPath", session).orElse(chatPath());
    }

    /**
     * The provider's own default wire, used when no {@code --protocol} (or the
     * default one) is configured. Base: the plain OpenAI-compatible shape. A
     * provider with its own dialect — mistral's rate-limit headers, ollama's
     * native chat, openrouter's envelope — overrides this and returns its class.
     */
    protected NaruModelProtocol createDefaultProtocol(NaruModelConfig model, NaruModelCapabilities capabilities, NaruSession session) {
        return new NaruModelProtocolOpenAICompat(this, model, name(),
                configValue("chatPath", session).orElse(chatPath()),
                capabilities, resolvedBaseUrl(session, defaultProtocol()));
    }

    /**
     * Whether the provider declares {@code protocolId} among the wires it can
     * build ({@link #supportedProtocols()}). The type's own default is always
     * implied, so it is not re-declared.
     */
    protected boolean supportsProtocolId(String protocolId) {
        Set<String> supported = supportedProtocols();
        if (supported == null) {
            return false;
        }
        if (protocolId != null && protocolId.equalsIgnoreCase(defaultProtocol())) {
            return true;
        }
        for (String s : supported) {
            if (s.equalsIgnoreCase(protocolId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The instance's wire protocol id: its own {@code --protocol} param
     * (registration or the {@code <id>.protocol} env key), case-normalised, else
     * the type's {@link #defaultProtocol()}.
     */
    protected String resolvedProtocolId(NaruSession session) {
        NOptional<String> protocol = configValue("protocol", session);
        if (protocol.isPresent() && !NBlankable.isBlank(protocol.get())) {
            return protocol.get().trim().toLowerCase();
        }
        return defaultProtocol();
    }

    /**
     * The base url the given wire shape talks to: the instance's own {@code url}
     * first (§6), then the wire class's own default base url (native gemini
     * points at Google itself), then the provider's {@link #baseUrl(NaruSession)}
     * (assumed to speak the type's default wire).
     */
    protected String resolvedBaseUrl(NaruSession session, String protocolId) {
        String own = configValue("url", session).orNull();
        if (!NBlankable.isBlank(own)) {
            return own.replaceAll("/+$", "");
        }
        if (NaruModelProtocolGeminiNative.PROTOCOL_ID.equalsIgnoreCase(protocolId)) {
            return NaruModelProtocolGeminiNative.DEFAULT_BASE_URL;
        }
        return baseUrl(session);
    }

    /**
     * The protocol-aware {@code /models} listing path. For the anthropic and
     * native-gemini wires the wire class's own path wins; for the openai wire —
     * the type's default or an explicit override — the provider's
     * {@link #modelsPath()} hook wins because the provider knows its own
     * endpoint layout.
     */
    protected String resolvedModelsPath(NaruSession session, String protocolId) {
        if (!NaruModelProtocolAnthropicCompat.PROTOCOL_ID.equalsIgnoreCase(protocolId)
                && !NaruModelProtocolGeminiNative.PROTOCOL_ID.equalsIgnoreCase(protocolId)) {
            return modelsPath();
        }
        if (NaruModelProtocolAnthropicCompat.PROTOCOL_ID.equalsIgnoreCase(protocolId)) {
            return NaruModelProtocolAnthropicCompat.modelsPath();
        }
        return NaruModelProtocolGeminiNative.modelsPath();
    }

    @Override
    public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
        if (!model.provider().equals(name())) {
            return NOptional.ofNamedEmpty(NMsg.ofC("protocol for %s", model));
        }
        NaruModelCapabilities capabilities = resolveCapabilities(model.model(), session);
        return NOptional.of(protocols.computeIfAbsent(model,
                k -> createProtocol(model, capabilities, session)));
    }

    public boolean isApiKeySet(NaruSession session){
        String apiKey = apiKey(session).orNull();
        if (NBlankable.isBlank(apiKey)) {
            return false;
        }
        return true;
    }
    @Override
    public List<String> findModelIds(NaruSession session) {
        if (!isApiKeySet(session)) {
            return Collections.emptyList();
        }
        return findModelIdsWithLiveFallback(session, new ArrayList<>());
    }

    protected NDuration connectTimeout(NaruSession session) {
        return configValue("connectTimeout", session).flatMap(x -> NDuration.of(x))
                .orElseGetOptionalFrom(() -> configValue("timeout", session).flatMap(x -> NDuration.of(x)))
                .orElse(NDuration.ofSeconds(120));
    }

    protected NDuration readTimeout(NaruSession session) {
        return configValue("readTimeout", session).flatMap(x -> NDuration.of(x))
                .orElseGetOptionalFrom(() -> configValue("timeout", session).flatMap(x -> NDuration.of(x)))
                .orElse(NDuration.ofSeconds(120));
    }


    protected String modelsPath() {
        return "models";
    }

    protected List<String> fetchLiveModelIds(NaruSession session) {
        String apiKey = apiKey(session).orNull();
        if (NBlankable.isBlank(apiKey)) {
            return Collections.emptyList();
        }
        String protocolId = resolvedProtocolId(session);
        if (!supportsProtocolId(protocolId)) {
            // the chat wire falls back to the provider's default when the requested
            // protocol is unsupported — enumerate against that same wire
            protocolId = defaultProtocol();
        }
        String path = resolvedModelsPath(session, protocolId);
        if (NBlankable.isBlank(path)) {
            return Collections.emptyList();
        }
        // the listing is a wire-level GET: auth header and parse shape belong to
        // the wire class, not to the provider (openai: Bearer + data[].id;
        // anthropic: x-api-key; native gemini: x-goog-api-key + models[].name)
        String authHeaderName;
        String authHeaderValue;
        Function<NElement, List<String>> idParser;
        if (NaruModelProtocolAnthropicCompat.PROTOCOL_ID.equalsIgnoreCase(protocolId)) {
            authHeaderName = NaruModelProtocolAnthropicCompat.authHeaderName();
            authHeaderValue = NaruModelProtocolAnthropicCompat.authHeaderValue(apiKey);
            idParser = NaruModelProtocolOpenAICompat::parseModelIds;
        } else if (NaruModelProtocolGeminiNative.PROTOCOL_ID.equalsIgnoreCase(protocolId)) {
            authHeaderName = NaruModelProtocolGeminiNative.authHeaderName();
            authHeaderValue = NaruModelProtocolGeminiNative.authHeaderValue(apiKey);
            idParser = NaruModelProtocolGeminiNative::parseModelIds;
        } else {
            authHeaderName = NaruModelProtocolOpenAICompat.authHeaderName();
            authHeaderValue = NaruModelProtocolOpenAICompat.authHeaderValue(apiKey);
            idParser = NaruModelProtocolOpenAICompat::parseModelIds;
        }
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(10))
                    .baseUri(resolvedBaseUrl(session, protocolId));

            NHttpRequest request = http.GET(path)
                    .timeout(NDuration.ofSeconds(10))
                    .header(authHeaderName, authHeaderValue);

            NHttpResponse response = request.run();
            if (response.isError()) {
                NHttpCode nHttpCode = response.statusCode();
                switch (nHttpCode.code()){
                    case 403:{
                        //just ignore, no balance!
                        break;
                    }
                    default:{
                        NLog.of(getClass()).log(NMsg.ofC(
                                "Failed to fetch live models from %s: HTTP %s %s",
                                name(), nHttpCode, response.statusMessage()
                        ).asWarning());
                    }
                }
                return Collections.emptyList();
            }

            NElement root = NElementReader.ofJson().read(response.contentAsString());
            return idParser.apply(root);
        } catch (Exception e) {
            NLog.of(getClass()).log(NMsg.ofC(
                    "Error fetching live models from %s: %s", name(), e.getMessage()
            ).asWarning());
            return Collections.emptyList();
        }
    }

    protected List<String> findModelIdsWithLiveFallback(NaruSession session, List<String> staticFallback) {
        if (!isApiKeySet(session)) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        String protocolId = resolvedProtocolId(session);
        String cacheKey = type() + "|" + protocolId + "|"
                + resolvedBaseUrl(session, protocolId) + "|" + apiKey(session).orNull();
        ModelCacheEntry cached = LIVE_MODELS_CACHE.get(cacheKey);
        if (cached != null && (now - cached.at) < LIVE_MODELS_TTL_MS) {
            return cached.ids;
        }
        List<String> live = fetchLiveModelIds(session);
        if (!live.isEmpty()) {
            LIVE_MODELS_CACHE.put(cacheKey, new ModelCacheEntry(live, now));
            return live;
        }
        return staticFallback;
    }

    // ── Reachability probe ─────────────────────────────────────────────────────

    /**
     * Short-timeout liveness probe on the given url with a small TTL cache.
     * Any HTTP response (even 4xx/5xx) means the endpoint is reachable;
     * connection failures/timeouts mean it is not.
     */
    protected boolean isReachable(String url) {
        long now = System.currentTimeMillis();
        ProbeResult cached = REACHABILITY_CACHE.get(url);
        if (cached != null && (now - cached.at) < REACHABILITY_TTL_MS) {
            return cached.ok;
        }
        boolean ok = probeUrl(url);
        REACHABILITY_CACHE.put(url, new ProbeResult(ok, now));
        return ok;
    }

    private boolean probeUrl(String url) {
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(3))
                    .baseUri(url);
            http.GET("/").timeout(NDuration.ofSeconds(3)).run();
            return true; // any response = reachable
        } catch (Exception e) {
            return false;
        }
    }

    private static class ProbeResult {
        final boolean ok;
        final long at;

        ProbeResult(boolean ok, long at) {
            this.ok = ok;
            this.at = at;
        }
    }

    private static class ModelCacheEntry {
        final List<String> ids;
        final long at;

        ModelCacheEntry(List<String> ids, long at) {
            this.ids = ids;
            this.at = at;
        }
    }
}
