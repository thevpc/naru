package net.thevpc.naru.impl.ia.model.ollama;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.impl.ia.model.NaruModelProtocolBase;

public class NaruModelProtocolOllamaNative extends NaruModelProtocolBase {
    public NaruModelProtocolOllamaNative(NaruModelProvider provider, NaruModelConfig model, String configPrefix, NaruModelCapabilities capabilities) {
        super(provider,model, configPrefix,
                "api/chat",
                capabilities,
                new NaruOllamaNativeRequestSerializer(),
                new NaruOllamaNativeResponseParser());
    }
}
