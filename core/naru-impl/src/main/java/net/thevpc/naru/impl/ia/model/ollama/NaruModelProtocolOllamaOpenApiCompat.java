package net.thevpc.naru.impl.ia.model.ollama;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.impl.ia.model.NaruModelProtocolBase;
import net.thevpc.naru.impl.ia.model.openapi.NaruOpenApiRequestSerializer;
import net.thevpc.naru.impl.ia.model.openapi.NaruOpenApiResponseParser;

public class NaruModelProtocolOllamaOpenApiCompat extends NaruModelProtocolBase {
    public NaruModelProtocolOllamaOpenApiCompat(NaruModelProvider provider, NaruModelConfig model, String configPrefix, NaruModelCapabilities capabilities) {
        super(provider,model, configPrefix,
                "v1/chat/completions",
                capabilities,
                new NaruOpenApiRequestSerializer(),
                new NaruOpenApiResponseParser()
        );
    }
}
