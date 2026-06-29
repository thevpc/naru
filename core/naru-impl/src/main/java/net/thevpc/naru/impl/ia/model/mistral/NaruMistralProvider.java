package net.thevpc.naru.impl.ia.model.mistral;


import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.impl.ia.model.NaruModelCapabilitiesImpl;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

public class NaruMistralProvider extends AbstractNaruModelProvider {

    private final Map<NaruModelConfig, NaruModelProtocol> protocols = new HashMap<>();
    private final List<String> supportedModels = new ArrayList<>();

    public NaruMistralProvider() {
        super("mistral");
        // Populating common production-tier Mistral models available in 2026
        supportedModels.add("mistral-medium-3.5");
        supportedModels.add("mistral-small-4");
        supportedModels.add("mistral-large-latest");
        supportedModels.add("codestral-latest");
    }

    @Override
    public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
        if (!model.provider().equals(name())) {
            return NOptional.ofNamedEmpty(NMsg.ofC("protocol for %s", model));
        }

        NaruModelCapabilities capabilities = getStaticCapabilities(model.model());
        return NOptional.of(protocols.computeIfAbsent(model,
                k -> new NaruModelProtocolMistral(NaruMistralProvider.this,model, name(), capabilities)
        ));
    }

    private String apiKeyConfigKey() {
        return name() + ".apiKey";
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        String apiKey = session.agent().env().get(apiKeyConfigKey())
                .flatMap(x -> x.asStringValue())
                .orNull();
        if (NBlankable.isBlank(apiKey)) {
            return Collections.emptyList();
        }
        return new ArrayList<>(supportedModels);
    }

    /**
     * Statically maps model limits since cloud-hosted capabilities cannot be polled natively.
     */
    private NaruModelCapabilities getStaticCapabilities(String modelName) {
        boolean vision = modelName.contains("small-4") || modelName.contains("medium-3.5");
        boolean tools = true;
        boolean thinking = modelName.contains("medium");
        boolean embedding = false;

        // 2026 standard context limits for modern Mistral architectures
        long contextLength = 262144L; // Default 262K context window (e.g. Large 3 / Medium 3.5)

        if (modelName.contains("small-4")) {
            contextLength = 256000L; // 256K for the unified small lines
        }

        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength);
    }
}