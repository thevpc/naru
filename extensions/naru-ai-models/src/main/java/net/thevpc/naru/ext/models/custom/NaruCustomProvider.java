package net.thevpc.naru.ext.models.custom;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openapi.AbstractOpenAICompatProvider;
import net.thevpc.naru.ext.models.openapi.NaruModelProtocolOpenAICompat;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

/**
 * Generic OpenAI-compatible provider driven entirely by configuration.
 * Lets users point NARU at any OpenAI-compatible endpoint
 * (LM Studio, vLLM, llama.cpp server, LiteLLM proxy, ...) with zero code.
 *
 * <p>Configuration (per named endpoint):
 * <pre>
 * custom.endpoints=&lt;name1&gt;,&lt;name2&gt;,...
 * custom.endpoints.&lt;name&gt;.url=https://my-server/v1
 * custom.endpoints.&lt;name&gt;.apiKey=sk-...
 * custom.endpoints.&lt;name&gt;.models=model-a,model-b
 * custom.endpoints.&lt;name&gt;.chatPath=chat/completions   (optional)
 * custom.endpoints.&lt;name&gt;.contextLength=32768         (optional)
 * </pre>
 *
 * <p>Models are addressed as {@code custom/&lt;endpoint&gt;/&lt;model&gt;}.
 */
public class NaruCustomProvider extends AbstractOpenAICompatProvider {

    private static final String PREFIX = "custom.endpoints.";

    private final Map<NaruModelConfig, NaruModelProtocol> protocols = new HashMap<>();

    public NaruCustomProvider() {
        super("custom",new String[0]);
    }

    /**
     * Splits a model id into (endpointName, realModel).
     * A model id without '/' addresses the implicit {@code default} endpoint.
     */
    private String[] split(String model) {
        int i = model.indexOf('/');
        if (i > 0) {
            return new String[]{model.substring(0, i), model.substring(i + 1)};
        }
        return new String[]{"default", model};
    }

    private List<String> endpoints(NaruSession session) {
        List<String> result = new ArrayList<>();
        session.agent().env().get("custom.endpoints").flatMap(x -> x.asStringValue())
                .ifPresent(v -> {
                    for (String s : v.split("[,\\s]+")) {
                        if (!s.isBlank()) {
                            result.add(s.trim());
                        }
                    }
                });
        return result;
    }

    @Override
    public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
        if (!model.provider().equals(name())) {
            return NOptional.ofNamedEmpty(NMsg.ofC("protocol for %s", model));
        }
        String[] parts = split(model.model());
        String endpoint = parts[0];
        String realModel = parts[1];
        String prefix = PREFIX + endpoint;

        String url = session.agent().env().get(prefix + ".url").flatMap(x -> x.asStringValue()).orNull();
        if (NBlankable.isBlank(url)) {
            return NOptional.ofNamedEmpty(NMsg.ofC(
                    "missing %s.url configuration for custom endpoint '%s'", prefix, endpoint));
        }

        NaruModelCapabilities capabilities = resolveCapabilities(model.model(), session);
        String chatPath = session.agent().env().get(prefix + ".chatPath").flatMap(x -> x.asStringValue())
                .map(p -> {
                    while (p.startsWith("/")) p = p.substring(1);
                    while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
                    return p;
                })
                .orElse("chat/completions");

        NaruModelConfig wireModel = model.withModel(realModel);
        return NOptional.of(protocols.computeIfAbsent(model,
                k -> new CustomProtocol(this, wireModel, prefix, chatPath, capabilities, url)
        ));
    }


    @Override
    public List<String> findModelIds(NaruSession session) {
        List<String> all = new ArrayList<>();
        for (String endpoint : endpoints(session)) {
            String prefix = PREFIX + endpoint;
            session.agent().env().get(prefix + ".models").flatMap(x -> x.asStringValue())
                    .ifPresent(v -> {
                        for (String s : v.split("[,\\s]+")) {
                            if (!s.isBlank()) {
                                all.add(endpoint.equals("default") ? s.trim() : endpoint + "/" + s.trim());
                            }
                        }
                    });
        }
        return all;
    }
    @Override
    protected String baseUrl(NaruSession session) {
        return "";
    }

    protected NaruModelCapabilities resolveCapabilities(String model, NaruSession session) {
        String[] parts = split(model);
        String prefix = PREFIX + parts[0];
        long contextLength = session.agent().env().get(prefix + ".contextLength")
                .flatMap(x -> x.asLongValue()).orElse(-1L);
        boolean tools = session.agent().env().get(prefix + ".tools")
                .flatMap(x -> x.asBooleanValue()).orElse(true);
        // tool-call emulation kicks in automatically when tools=false
        return new NaruModelCapabilitiesImpl(false, tools, false, false, contextLength);
    }

    static class CustomProtocol extends NaruModelProtocolOpenAICompat {

        CustomProtocol(NaruCustomProvider provider, NaruModelConfig model, String configPrefix,
                       String chatPath, NaruModelCapabilities capabilities, String defaultBaseUrl) {
            super(provider, model, configPrefix, chatPath, capabilities, defaultBaseUrl);
        }
    }
}
