package net.thevpc.naru.impl.model.ollama;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.impl.model.NaruModelProtocolBase;
import net.thevpc.naru.impl.model.openapi.NaruOpenApiRequestSerializer;
import net.thevpc.naru.impl.model.openapi.NaruOpenApiResponseParser;

public class NaruModelProtocolOllamaOpenApiCompat extends NaruModelProtocolBase {
    public NaruModelProtocolOllamaOpenApiCompat(NaruModelConfig model, String configPrefix, NaruModelCapabilities capabilities) {
        super(model, configPrefix,
                "v1/chat/completions",
                capabilities,
                new NaruOpenApiRequestSerializer(),
                new NaruOpenApiResponseParser()
        );
    }
}
