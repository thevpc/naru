package net.thevpc.naru.impl.model.gemini;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.impl.model.openapi.NaruModelProtocolOpenAICompat;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.log.NLog;
import net.thevpc.nuts.net.NWebCli;
import net.thevpc.nuts.net.NWebRequest;
import net.thevpc.nuts.net.NWebResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.Map;

public class NaruModelProtocolGemini extends NaruModelProtocolOpenAICompat {

    public NaruModelProtocolGemini(NaruModelConfig model, String baseUrl, NaruModelCapabilities capabilities) {
        super(model, baseUrl, "chat/completions", capabilities);
    }

    @Override
    public String url(NaruTask task, Map<String,NElement> env) {
        // Gemini's OpenAI-compatible base endpoint
        String url = task.session().agent().env().get(configPrefix + ".url")
                .flatMap(x -> x.asStringValue())
                .orElse("https://generativelanguage.googleapis.com/v1beta/openai");
        return url.replaceAll("/$", "");
    }

    private String apiKeyConfigKey() {
        return configPrefix + ".apiKey";
    }

    @Override
    public NaruResponse chat(NaruModelRequest naruModelRequest, NaruTask task) {
        // Retrieve the API Key safely from the Naru Environment
        String apiKey = task.session().agent().env().get(apiKeyConfigKey())
                .flatMap(x -> x.asStringValue())
                .orElseThrow(() -> new NIllegalArgumentException(
                        NMsg.ofC("Missing required Gemini API Key configuration: %s", apiKeyConfigKey())
                ));

        // Use the inherited robust OpenAI-compatible payload builder
        NElement body = serializer.serialize(naruModelRequest,model, task.session());
        Map<String, NElement> env = naruModelRequest.env();
        NWebCli http = NWebCli.of()
                .connectTimeout(connectTimeout(task,env))
                .baseUri(url(task,env));

        // OpenAI compatibility router maps this to the standard /chat/completions route
        NWebRequest request = http.POST(chatPath)
                .header("Authorization", "Bearer " + apiKey)
                .timeout(readTimeout(task,env))
                .jsonRequestBody(body);

        String responseString = null;
        try {
            NWebResponse response = request.run().ifErrorThrow();
            responseString = response.contentAsString();
            return parseResponse(responseString);
        } catch (Exception e) {
            NLog.of(NaruModelProtocolGemini.class)
                    .log(
                            NMsg.ofC("Failed to communicate with Gemini at %s: %s\n-----BODY\n%s\n-----BODY\n-----RESPONSE\n%s\n-----RESPONSE", request.effectiveUri(), e.getMessage(), e,
                                    NElementWriter.ofJson().formatPlain(body),
                                    responseString
                            ).asError()
                    );
            throw new NIllegalArgumentException(
                    NMsg.ofC("Failed to communicate with Gemini at %s: %s", request.effectiveUri(), e.getMessage(), e)
            );
        }
    }
}
