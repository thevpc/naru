package net.thevpc.naru.ext.models.openapi;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.NaruModelProtocolType;
import net.thevpc.naru.ext.models.NaruModelProtocolTypes;
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

/**
 * Base class for OpenAI-compatible cloud providers (Groq, Cerebras, OpenRouter, GitHub Models, ...).
 * Subclasses provide a name, a default base url, a model list and static capabilities;
 * the wire protocol and auth handling are inherited.
 */
public abstract class AbstractOpenAICompatProvider extends AbstractNaruModelProvider {
    /**
     * Live model enumeration cached per (type, url, key) — design §9: several
     * registrations of one type that share a key and endpoint cost one listing,
     * not one each, while a registration with its own key gets its own entry.
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
     * Fallback base url when {@code <name>.url} config is not set.
     */
    protected abstract String baseUrl(NaruSession session);

    /**
     * Statically maps capabilities since cloud-hosted capabilities cannot be polled natively.
     */
    protected abstract NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session);

    protected NaruModelProtocol createProtocol(NaruModelConfig model, NaruModelCapabilities capabilities, NaruSession session) {
        // a registration's chatPath param wins over the type's default path (§7)
        String cp = configValue("chatPath", session).orElse(chatPath());
        return new NaruModelProtocolOpenAICompat(this, model, name(), cp, capabilities, baseUrl(session));
    }

    @Override
    public Set<String> supportedProtocols() {
        // ids the protocol factory actually resolves against, so /model add can
        // reject an unknown --protocol — and say what it knows — instead of
        // silently defaulting (§8)
        return NaruModelProtocolTypes.names();
    }

    @Override
    public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
        if (!model.provider().equals(name())) {
            return NOptional.ofNamedEmpty(NMsg.ofC("protocol for %s", model));
        }
        NaruModelCapabilities capabilities = resolveCapabilities(model.model(), session);
        return NOptional.of(protocols.computeIfAbsent(model,
                k -> {
                    NOptional<String> override = configValue("protocol", session);
                    if (override.isPresent()) {
                        return overriddenProtocol(override.get(), model, capabilities, session);
                    }
                    return createProtocol(model, capabilities, session);
                }
        ));
    }

    /**
     * The instance's {@code --protocol} override (design §8): same provider type,
     * another wire shape — a gemini registration speaking native
     * {@code generateContent}, a custom endpoint speaking Anthropic Messages.
     *
     * <p>The id must name a type this provider knows: {@code /model add} rejects
     * unknown ids, and a hand-edited registration fails loudly here rather than
     * silently falling back to the default shape.
     */
    private NaruModelProtocol overriddenProtocol(String protocolId, NaruModelConfig model,
                                                 NaruModelCapabilities capabilities, NaruSession session) {
        NaruModelProtocolType t = NaruModelProtocolTypes.of(protocolId).orElse(null);
        if (t == null) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "unknown protocol '%s' for provider '%s'", protocolId, name()));
        }
        String cp = configValue("chatPath", session).orNull();
        if (cp == null) {
            if (NaruModelProtocolTypes.ANTHROPIC.equals(protocolId)) {
                cp = "v1/messages";
            } else if (NaruModelProtocolTypes.GEMINI.equals(protocolId)) {
                cp = null; // native protocol builds models/<id>:generateContent itself
            } else {
                cp = chatPath();
            }
        }
        return t.create(this, model, name(), cp, capabilities, baseUrl(session));
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
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(10))
                    .baseUri(baseUrl(session));

            NHttpRequest request = http.GET(modelsPath())
                    .timeout(NDuration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey); // confirm against prepareRequest()

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
            NOptional<NElement> dataOpt = root.asObject().flatMap(o -> o.get("data"));
            if (!dataOpt.isPresent() || !dataOpt.get().isArray()) {
                return Collections.emptyList();
            }

            List<String> ids = new ArrayList<>();
            for (NElement item : dataOpt.get().asArray().get()) {
                item.asObject().flatMap(o -> o.get("id"))
                        .flatMap(NElement::asStringValue)
                        .ifPresent(ids::add);
            }
            return ids;
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
        String cacheKey = type() + "|" + baseUrl(session) + "|" + apiKey(session).orNull();
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
