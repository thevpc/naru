package net.thevpc.naru.impl.model.ollama;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.impl.model.NaruModelProtocolBase;

public class NaruModelProtocolOllamaNative extends NaruModelProtocolBase {
    public NaruModelProtocolOllamaNative(NaruModelConfig model, String configPrefix, NaruModelCapabilities capabilities) {
        super(model, configPrefix,
                "api/chat",
                capabilities,
                new NaruOllamaNativeRequestSerializer(),
                new NaruOllamaNativeResponseParser());
    }
}
