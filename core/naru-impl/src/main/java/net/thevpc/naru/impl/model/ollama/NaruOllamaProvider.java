package net.thevpc.naru.impl.model.ollama;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.impl.model.NaruModelCapabilitiesImpl;
import net.thevpc.naru.impl.model.openapi.NaruModelProtocolOpenAICompat;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.log.NLog;
import net.thevpc.nuts.net.NWebCli;
import net.thevpc.nuts.net.NWebRequest;
import net.thevpc.nuts.net.NWebResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NChronometer;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

/**
 * Ollama provider — talks to a local (or remote) Ollama server via REST.
 *
 * <p>Endpoint: POST {baseUrl}/api/chat
 * <p>Compatible with Ollama 0.2.8+ tool-calling format.
 */
public class NaruOllamaProvider extends AbstractNaruModelProvider {

    //    private NWebCli http;
    private final Map<NaruModelConfig, NaruModelProtocol> protocols = new HashMap<>();
    private final Map<String, NaruModelCapabilities> cachedCapabilities = new HashMap<>();
    private final NElementReader nElementReader;

    public NaruOllamaProvider() {
        super("ollama");
        nElementReader = NElementReader.ofJson();
    }


    @Override
    public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
        if (!model.provider().equals(getName())) {
            return NOptional.ofNamedEmpty(NMsg.ofC("protocol for %s", model));
        }
        NaruModelCapabilities capabilities = getCapabilities(model.model(), session);
        if (capabilities.isVision()) {
            return NOptional.of(protocols.computeIfAbsent(model, k -> new NaruModelProtocolOpenAICompat(model, getName(), capabilities)));
        }
        return NOptional.of(protocols.computeIfAbsent(model, k -> new NaruModelProtocolOllamaNative(model, getName(), capabilities)));
    }


    private String url(NaruSession session) {
        String url = session.agent().env().get(getName() + ".url").flatMap(x -> x.asStringValue()).orElse("http://localhost:11434");
        return url.replaceAll("/$", "");
    }

    private NDuration connectTimeout(NaruSession session) {
        return session.agent().env().get(getName() + ".connectTimeout").flatMap(x -> x.asStringValue())
                .flatMap(x -> NDuration.parse(x))
                .orElseGetOptionalFrom(
                        () -> session.agent().env().get(getName() + ".timeout").flatMap(x -> x.asStringValue())
                                .flatMap(x -> NDuration.parse(x))
                )
                .orElse(NDuration.ofSeconds(30));
    }

    private NDuration readTimeout(NaruSession session) {
        return session.agent().env().get(getName() + ".readTimeout").flatMap(x -> x.asStringValue())
                .flatMap(x -> NDuration.parse(x))
                .orElseGetOptionalFrom(
                        () -> session.agent().env().get(getName() + ".timeout").flatMap(x -> x.asStringValue())
                                .flatMap(x -> NDuration.parse(x))
                )
                .orElse(NDuration.ofSeconds(30));
    }

    public NaruModelCapabilities getCapabilities(String model, NaruSession session) {
        NaruModelCapabilities c = cachedCapabilities.get(model);
        if (c != null) {
            return c;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        NWebCli http = NWebCli.of()
                .connectTimeout(connectTimeout(session))
                .prefix(url(session));
        NWebRequest request = http.POST("api/show")
                .timeout(readTimeout(session))
                .jsonRequestBody(body);
        try {
            NChronometer chrono = NChronometer.of();
            NaruUtils.logWebRequest(request,NMsg.ofC("checking capabilities of %s\n%s", model),body);
            NWebResponse response = request.run().ifErrorThrow();
            String json = response.contentAsString();
            NElement root = nElementReader.read(json);
            NaruModelCapabilities naruModelCapabilities = parseCapabilities(root);
            cachedCapabilities.put(model, naruModelCapabilities);
            NaruUtils.logWebResponse(request,NMsg.ofC("checking capabilities of %s\n%s", json),body,chrono);
            return naruModelCapabilities;
        } catch (Exception e) {
            // fallback — assume minimal capabilities
            return NaruModelCapabilitiesImpl.UNKNOWN;
        }
    }

    private NaruModelCapabilities parseCapabilities(NElement root) {
        if (!root.isAnyObject()) return NaruModelCapabilitiesImpl.UNKNOWN;

        NArrayElement arr = root.asObject().get().getArray("capabilities").orNull();
        if (arr == null) return NaruModelCapabilitiesImpl.UNKNOWN;

        boolean vision = false, tools = false, thinking = false, embedding = false;
        for (NElement el : arr) {
            String cap = el.asStringValue().orNull();
            if (cap == null) continue;
            switch (cap) {
                case "vision":
                    vision = true;
                    break;
                case "tools":
                    tools = true;
                    break;
                case "thinking":
                    thinking = true;
                    break;
                case "embedding":
                    embedding = true;
                    break;
            }
        }
        // in parseCapabilities or a separate method
        NObjectElement modelInfo = root.asObject().get().getObject("model_info").orNull();
        long contextLength = -1;
        if (modelInfo != null) {
            // key is "<arch>.context_length", scan for it
            for (NPairElement entry : modelInfo.namedPairs()) {
                if (entry.key().asStringValue().orElse("").endsWith(".context_length")) {
                    contextLength = entry.value().asLongValue().orElse(0L);
                }
            }
        }
        String parameters = root.asObject().get().getStringValue("parameters").orNull();
        long numCtx = parseNumCtx(parameters);
        if (numCtx > 0) {
            contextLength = numCtx;
        } else {
            // fall back to model_info arch context_length
        }

        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength);
    }

    private long parseNumCtx(String parameters) {
        if (parameters == null) return 0;
        for (String line : parameters.split("\n")) {
            line = line.trim();
            if (line.startsWith("num_ctx")) {
                line.replace('=', ' ').trim();
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Long.parseLong(parts[parts.length - 1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return 0;
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        NWebCli http = NWebCli.of()
                .connectTimeout(NDuration.ofSeconds(30))
                .prefix(url(session));
        NWebRequest request = http.GET("api/tags")
                .connectTimeout(NDuration.ofSeconds(10))
                .readTimeout(NDuration.ofSeconds(10));
        try {
            NWebResponse response = request.run().ifErrorThrow();
            String json = response.contentAsString();
            NElement root = nElementReader.read(json);
            List<String> models = new ArrayList<>();
            if (root.isAnyObject()) {
                NArrayElement modelsArr = root.asObject().get().getArray("models").orNull();
                if (modelsArr != null) {
                    for (NElement el : modelsArr) {
                        el.asObject().ifPresent(obj -> {
                            obj.getStringValue("name").ifPresent(models::add);
                        });
                    }
                }
            }
            return models;
        } catch (Exception e) {
            // If we can't list models, return an empty list or a default
            return new ArrayList<>();
        }
    }


}
