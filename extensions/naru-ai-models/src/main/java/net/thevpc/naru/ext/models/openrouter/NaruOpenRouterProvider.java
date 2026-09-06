package net.thevpc.naru.ext.models.openrouter;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openapi.AbstractOpenAICompatProvider;
import net.thevpc.naru.ext.models.openapi.NaruModelProtocolOpenAICompat;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenRouter provider — one API key gives access to hundreds of models,
 * including a rotating set of {@code :free} variants.
 *
 * <p>Endpoint: POST {baseUrl}/chat/completions
 * <p>Default baseUrl: https://openrouter.ai/api/v1
 */
public class NaruOpenRouterProvider extends AbstractOpenAICompatProvider {

    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";

    private final Map<String, NaruModelCapabilities> cachedCapabilities = new ConcurrentHashMap<>();

    public NaruOpenRouterProvider() {
        super("openrouter",new String[]{"OPENROUTER_API_KEY"});
    }

    private NElementReader elementReader() {
        return NElementReader.ofJson();
    }

    @Override
    protected NaruModelProtocol createProtocol(NaruModelConfig model, NaruModelCapabilities capabilities, NaruSession session) {
        return new NaruOpenRouterProtocol(this, model, name(), capabilities);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        if (!isApiKeySet(session)) {
            return Collections.emptyList();
        }
        String apiKey = apiKey(session).orNull();
        if (NBlankable.isBlank(apiKey)) {
            return Collections.emptyList();
        }

        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(10))
                .baseUri(baseUrl(session));
        NHttpRequest request = http.GET("models")
                .readTimeout(NDuration.ofSeconds(30));
        request.header("Authorization", "Bearer " + apiKey);

        try {
            NHttpResponse response = request.run().ifErrorThrow();
            NElement root = elementReader().read(response.contentAsString());

            boolean freeOnly = isFreeOnlyConfigured(session);
            List<String> includeFilters = parseFilterList(getFilterString(session));
            List<String> excludeFilters = parseFilterList(getExcludeString(session));
            int limit = getLimitConfigured(session);

            List<String> free = new ArrayList<>();
            List<String> paid = new ArrayList<>();

            root.asObject().flatMap(o -> o.getArray("data")).ifPresent(arr -> {
                for (NElement el : arr.children()) {
                    if (!el.isAnyObject()) continue;
                    NObjectElement obj = el.asObject().get();
                    String id = obj.getStringValue("id").orElse(null);
                    if (id == null || id.isBlank()) continue;

                    boolean isFree = id.endsWith(":free");
                    NObjectElement pricing = obj.getObject("pricing").orNull();
                    if (pricing != null) {
                        String promptPrice = pricing.getStringValue("prompt").orElse("");
                        String compPrice = pricing.getStringValue("completion").orElse("");
                        if (("0".equals(promptPrice) || "0.0".equals(promptPrice)) &&
                                ("0".equals(compPrice) || "0.0".equals(compPrice))) {
                            isFree = true;
                        }
                    }

                    // Parse capabilities from OpenRouter JSON
                    long contextLength = obj.getLongValue("context_length").orElse(-1L);
                    boolean vision = id.contains("vision") || id.contains("vl");
                    NObjectElement arch = obj.getObject("architecture").orNull();
                    if (arch != null) {
                        NArrayElement inModalities = arch.getArray("input_modalities").orNull();
                        if (inModalities != null) {
                            for (NElement m : inModalities) {
                                String s = m.asStringValue().orElse("");
                                if ("image".equalsIgnoreCase(s) || "video".equalsIgnoreCase(s)) {
                                    vision = true;
                                    break;
                                }
                            }
                        }
                    }

                    boolean tools = true;
                    boolean thinking = id.contains("thinking") || id.contains("r1") || id.contains("qwq")
                            || id.contains("o1") || id.contains("o3");
                    NArrayElement params = obj.getArray("supported_parameters").orNull();
                    if (params != null) {
                        boolean hasToolsParam = false;
                        for (NElement p : params) {
                            String paramName = p.asStringValue().orElse("");
                            if ("tools".equalsIgnoreCase(paramName)) {
                                hasToolsParam = true;
                            }
                            if ("reasoning".equalsIgnoreCase(paramName) || "include_reasoning".equalsIgnoreCase(paramName)) {
                                thinking = true;
                            }
                        }
                        tools = hasToolsParam;
                    }

                    cachedCapabilities.put(id, new NaruModelCapabilitiesImpl(vision, tools, thinking, false, contextLength));

                    // Filter application
                    if (freeOnly && !isFree) {
                        continue;
                    }

                    if (!matchesAny(id, includeFilters, true)) {
                        continue;
                    }

                    if (matchesAny(id, excludeFilters, false)) {
                        continue;
                    }

                    if (isFree) {
                        free.add(id);
                    } else {
                        paid.add(id);
                    }
                }
            });

            Collections.sort(free);
            Collections.sort(paid);

            List<String> all = new ArrayList<>(free);
            all.addAll(paid);

            if (limit > 0 && all.size() > limit) {
                return all.subList(0, limit);
            }
            return all;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return session.agent().env().get(name() + ".url")
                .flatMap(NElement::asStringValue)
                .map(x -> x.replaceAll("/$", ""))
                .orElse(DEFAULT_BASE_URL);
    }

    private NOptional<NElement> getEnv(NaruSession session, String... keys) {
        for (String key : keys) {
            NOptional<NElement> val = session.agent().env().get(key);
            if (val != null && val.isPresent()) {
                return val;
            }
        }
        return NOptional.ofEmpty();
    }

    private boolean isFreeOnlyConfigured(NaruSession session) {
        return getEnv(session, name() + ".freeOnly", name() + ".onlyFree")
                .flatMap(NElement::asBooleanValue)
                .orElse(false);
    }

    private String getFilterString(NaruSession session) {
        return getEnv(session, name() + ".filter", name() + ".models", name() + ".include")
                .flatMap(NElement::asStringValue)
                .orNull();
    }

    private String getExcludeString(NaruSession session) {
        return getEnv(session, name() + ".exclude")
                .flatMap(NElement::asStringValue)
                .orNull();
    }

    private int getLimitConfigured(NaruSession session) {
        return getEnv(session, name() + ".limit", name() + ".maxModels")
                .flatMap(NElement::asIntValue)
                .orElse(-1);
    }

    private List<String> parseFilterList(String filterString) {
        if (NBlankable.isBlank(filterString)) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (String s : filterString.split("[,;\\s]+")) {
            if (!s.isBlank()) {
                list.add(s.trim().toLowerCase());
            }
        }
        return list;
    }

    private boolean matchesAny(String modelId, List<String> filters, boolean defaultIfEmpty) {
        if (filters == null || filters.isEmpty()) {
            return defaultIfEmpty;
        }
        String idLower = modelId.toLowerCase();
        for (String f : filters) {
            if (f.equals("*")) return true;
            if (f.startsWith("*") && f.endsWith("*") && f.length() > 2) {
                if (idLower.contains(f.substring(1, f.length() - 1))) return true;
            } else if (f.endsWith("*")) {
                if (idLower.startsWith(f.substring(0, f.length() - 1))) return true;
            } else if (f.startsWith("*")) {
                if (idLower.endsWith(f.substring(1))) return true;
            } else {
                if (idLower.contains(f)) return true;
            }
        }
        return false;
    }

    public NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        NaruModelCapabilities cached = cachedCapabilities.get(modelName);
        if (cached != null) {
            return cached;
        }

        boolean vision = modelName.contains("vision") || modelName.contains("vl")
                || modelName.contains("gemini") || modelName.contains("gpt-4o");
        boolean tools = true;
        boolean thinking = modelName.contains("thinking") || modelName.contains("r1")
                || modelName.contains("qwq") || modelName.contains("o1") || modelName.contains("o3");
        boolean embedding = false;
        long contextLength = -1;

        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength);
    }

    static class NaruOpenRouterProtocol extends NaruModelProtocolOpenAICompat {

        NaruOpenRouterProtocol(NaruOpenRouterProvider provider, NaruModelConfig model, String configPrefix, NaruModelCapabilities capabilities) {
            super(provider, model, configPrefix, "chat/completions", capabilities, DEFAULT_BASE_URL);
        }

        @Override
        protected void prepareRequest(NHttpRequest request, NElement body, NaruTask task) {
            super.prepareRequest(request, body, task);
            // Recommended attribution headers (optional, configurable)
            task.session().agent().env().get(configPrefix + ".httpReferer").flatMap(x -> x.asStringValue())
                    .ifPresent(v -> request.header("HTTP-Referer", v));
            task.session().agent().env().get(configPrefix + ".xTitle").flatMap(x -> x.asStringValue())
                    .ifPresent(v -> request.header("X-Title", v));
        }
    }
}
