package net.thevpc.naru.impl.ia.model.openapi;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.impl.ia.model.NaruModelProtocolBase;

public class NaruModelProtocolOpenAICompat extends NaruModelProtocolBase {

    public NaruModelProtocolOpenAICompat(NaruModelProvider provider,NaruModelConfig model, String baseUrl, String chatPath, NaruModelCapabilities capabilities) {
        super(provider,model, baseUrl, chatPath, capabilities,
                new NaruOpenApiRequestSerializer(),
                new NaruOpenApiResponseParser()
        );
    }




}
