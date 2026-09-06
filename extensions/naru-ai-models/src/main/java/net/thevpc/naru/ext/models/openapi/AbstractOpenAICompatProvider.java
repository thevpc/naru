package net.thevpc.naru.ext.models.openapi;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
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
import net.thevpc.nuts.util.NOptional;

import java.util.*;

/**
 * Base class for OpenAI-compatible cloud providers (Groq, Cerebras, OpenRouter, GitHub Models, ...).
 * Subclasses provide a name, a default base url, a model list and static capabilities;
 * the wire protocol and auth handling are inherited.
 */
public abstract class AbstractOpenAICompatProvider extends AbstractNaruModelProvider {
    private volatile List<String> cachedLiveModels;
    private volatile long cachedLiveModelsAt = 0L;
    private static final long LIVE_MODELS_TTL_MS = 5 * 60 * 1000L;

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
        return new NaruModelProtocolOpenAICompat(this, model, name(), chatPath(), capabilities, baseUrl(session));
    }

    @Override
    public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
        if (!model.provider().equals(name())) {
            return NOptional.ofNamedEmpty(NMsg.ofC("protocol for %s", model));
        }
        NaruModelCapabilities capabilities = resolveCapabilities(model.model(), session);
        return NOptional.of(protocols.computeIfAbsent(model,
                k -> createProtocol(model, capabilities, session)
        ));
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
        return session.agent().env().get(name() + ".connectTimeout").flatMap(x -> x.asStringValue())
                .flatMap(x -> NDuration.of(x))
                .orElseGetOptionalFrom(
                        () -> session.agent().env().get(name() + ".timeout").flatMap(x -> x.asStringValue())
                                .flatMap(x -> NDuration.of(x))
                )
                .orElse(NDuration.ofSeconds(120));
    }

    protected NDuration readTimeout(NaruSession session) {
        return session.agent().env().get(name() + ".readTimeout").flatMap(x -> x.asStringValue())
                .flatMap(x -> NDuration.of(x))
                .orElseGetOptionalFrom(
                        () -> session.agent().env().get(name() + ".timeout").flatMap(x -> x.asStringValue())
                                .flatMap(x -> NDuration.of(x))
                )
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
        List<String> cached = cachedLiveModels;
        if (cached != null && (now - cachedLiveModelsAt) < LIVE_MODELS_TTL_MS) {
            return cached;
        }
        List<String> live = fetchLiveModelIds(session);
        if (!live.isEmpty()) {
            cachedLiveModels = live;
            cachedLiveModelsAt = now;
            return live;
        }
        return staticFallback;
    }
}
