package net.thevpc.naru.ext.models.custom;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.NaruModelProtocolType;
import net.thevpc.naru.ext.models.NaruModelProtocolTypes;
import net.thevpc.naru.ext.models.openapi.AbstractOpenAICompatProvider;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

/**
 * Generic config-driven provider: lets users point NARU at any wire-compatible
 * endpoint (OpenAI-compatible, Anthropic Messages, ...) with zero code.
 *
 * <p>Configuration (per named endpoint):
 * <pre>
 * custom.endpoints=&lt;name1&gt;,&lt;name2&gt;,...
 * custom.endpoints.&lt;name&gt;.url=https://my-server
 * custom.endpoints.&lt;name&gt;.type=openapi|anthropic   (optional, default: openapi)
 * custom.endpoints.&lt;name&gt;.apiKey=sk-...
 * custom.endpoints.&lt;name&gt;.models=model-a,model-b
 * custom.endpoints.&lt;name&gt;.chatPath=v1/chat/completions   (optional)
 * custom.endpoints.&lt;name&gt;.contextLength=32768             (optional)
 * custom.endpoints.&lt;name&gt;.tools=true                      (optional)
 * custom.endpoints.&lt;name&gt;.probe=true                      (optional, default: true)
 * </pre>
 *
 * <p>Models are addressed as {@code custom/&lt;endpoint&gt;/&lt;model&gt;}.
 *
 * <p>By default each endpoint is probed (short-timeout GET on its url + TTL cache)
 * before its models show up in listings; set {@code probe=false} to opt out.
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

        String type = endpointType(session, prefix);
        NaruModelProtocolType protocolType = NaruModelProtocolTypes.of(type).orElse(NaruModelProtocolTypes.defaultType());

        NaruModelCapabilities capabilities = resolveCapabilities(model.model(), session);
        String chatPath = session.agent().env().get(prefix + ".chatPath").flatMap(x -> x.asStringValue())
                .map(p -> {
                    while (p.startsWith("/")) p = p.substring(1);
                    while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
                    return p;
                })
                .orElse(defaultChatPath(type));

        NaruModelConfig wireModel = model.withModel(realModel);
        return NOptional.of(protocols.computeIfAbsent(model,
                k -> protocolType.create(this, wireModel, prefix, chatPath, capabilities, url)
        ));
    }

    private String endpointType(NaruSession session, String prefix) {
        return session.agent().env().get(prefix + ".type").flatMap(x -> x.asStringValue())
                .map(s -> s.trim().toLowerCase())
                .orElse(NaruModelProtocolTypes.OPENAPI);
    }

    private String defaultChatPath(String type) {
        if (NaruModelProtocolTypes.ANTHROPIC.equals(type)) {
            return "v1/messages";
        }
        return "chat/completions";
    }

    @Override
    public boolean isAvailable(NaruSession session) {
        List<String> eps = endpoints(session);
        if (eps.isEmpty()) {
            // nothing configured -> nothing to hide
            return true;
        }
        for (String endpoint : eps) {
            if (isEndpointUsable(endpoint, session)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEndpointUsable(String endpoint, NaruSession session) {
        String prefix = PREFIX + endpoint;
        boolean probe = session.agent().env().get(prefix + ".probe")
                .flatMap(x -> x.asBooleanValue()).orElse(true);
        if (!probe) {
            return true;
        }
        String url = session.agent().env().get(prefix + ".url").flatMap(x -> x.asStringValue()).orNull();
        return !NBlankable.isBlank(url) && isReachable(url);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        List<String> all = new ArrayList<>();
        for (String endpoint : endpoints(session)) {
            if (!isEndpointUsable(endpoint, session)) {
                continue;
            }
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
}