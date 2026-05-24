package net.thevpc.naru.impl.model.openapi;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.impl.model.NaruModelProtocolBase;

public class NaruModelProtocolOpenAICompat extends NaruModelProtocolBase {

    public NaruModelProtocolOpenAICompat(NaruModelConfig model, String baseUrl, String chatPath, NaruModelCapabilities capabilities) {
        super(model, baseUrl, chatPath, capabilities,
                new NaruOpenApiRequestSerializer(),
                new NaruOpenApiResponseParser()
        );
    }




}
