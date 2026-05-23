package net.thevpc.naru.impl.model.ollama;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.impl.model.openapi.NaruModelProtocolOpenApiBase;

public class NaruModelProtocolOllamaNative extends NaruModelProtocolOpenApiBase {
    public NaruModelProtocolOllamaNative(NaruModelConfig model, String configPrefix, NaruModelCapabilities capabilities) {
        super(model, configPrefix, capabilities,new NaruOllamaResponseParser());
    }
}
