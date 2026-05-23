package net.thevpc.naru.impl.model.gemini;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.impl.model.NaruModelCapabilitiesImpl;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

/**
 * Ollama provider — talks to a local (or remote) Ollama server via REST.
 *
 * <p>Endpoint: POST {baseUrl}/api/chat
 * <p>Compatible with Ollama 0.2.8+ tool-calling format.
 */
public class NaruGeminiProvider extends AbstractNaruModelProvider {

    private final Map<NaruModelConfig, NaruModelProtocol> protocols = new HashMap<>();
    private final List<String> supportedModels = new ArrayList<>();

    public NaruGeminiProvider() {
        super("gemini");
        // Populating common production-tier Gemini models available in 2026
        supportedModels.add("gemini-2.5-flash");
        supportedModels.add("gemini-3.5-flash");
        supportedModels.add("gemini-1.5-pro");
        supportedModels.add("gemini-2.5-pro");
    }

    @Override
    public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
        if (!model.provider().equals(getName())) {
            return NOptional.ofNamedEmpty(NMsg.ofC("protocol for %s", model));
        }

        NaruModelCapabilities capabilities = getStaticCapabilities(model.model());
        return NOptional.of(protocols.computeIfAbsent(model,
                k -> new NaruModelProtocolGemini(model, getName(), capabilities)
        ));
    }
    private String apiKeyConfigKey(){
        return getName() + ".apiKey";
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        String apiKey = session.agent().env().get(apiKeyConfigKey())
                .flatMap(x -> x.asStringValue())
                .orNull();
        if(NBlankable.isBlank(apiKey)){
            return Collections.emptyList();
        }
        return new ArrayList<>(supportedModels);
    }

    /**
     * Statically maps model limits since cloud-hosted capabilities cannot be polled natively.
     */
    private NaruModelCapabilities getStaticCapabilities(String modelName) {
        boolean vision = true;
        boolean tools = true;
        boolean thinking = modelName.contains("pro");
        boolean embedding = false;
        long contextLength = 1048576L; // 1M tokens standard fallback for Flash lines

        if (modelName.contains("1.5-pro") || modelName.contains("2.5-pro")) {
            contextLength = 2097152L; // 2M tokens context window for Pro tiers
        }

        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength);
    }
}