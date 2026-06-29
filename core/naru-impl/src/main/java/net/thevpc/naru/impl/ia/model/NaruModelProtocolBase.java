package net.thevpc.naru.impl.ia.model;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.net.NWebCli;
import net.thevpc.nuts.net.NWebRequest;
import net.thevpc.nuts.net.NWebResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NChronometer;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NaruModelProtocolBase implements NaruModelProtocol {
    protected final NElementReader nElementReader;
    protected final NaruModelConfig model;
    protected final NaruModelCapabilities capabilities;
    protected final String configPrefix;
    protected final String chatPath;
    protected final NaruModelRequestSerializer serializer;
    protected final NaruModelProvider provider;


    public NaruModelProtocolBase(NaruModelProvider provider,NaruModelConfig model, String configPrefix,
                                 String chatPath,
                                 NaruModelCapabilities capabilities,
                                 NaruModelRequestSerializer serializer,
                                 NElementDeserializer<NaruResponse> responseParser) {
        this.provider = provider;
        this.model = model;
        this.capabilities = capabilities;
        this.configPrefix = configPrefix;
        this.chatPath = prepareUrlPrefix(chatPath);
        this.nElementReader = NElementReader.ofJson();
        this.serializer = serializer;
        this.nElementReader.mapperStore().setDeserializer(NaruResponse.class, responseParser);
    }

    public NaruModelProvider provider() {
        return provider;
    }

    private String prepareUrlPrefix(String urlPrefix) {
        if (NBlankable.isBlank(urlPrefix)) {
            return null;
        }
        while (urlPrefix.startsWith("/")) {
            urlPrefix = urlPrefix.substring(1).trim();
        }
        while (urlPrefix.endsWith("/")) {
            urlPrefix = urlPrefix.substring(0, urlPrefix.length() - 1).trim();
        }
        if (NBlankable.isBlank(urlPrefix)) {
            return null;
        }
        return urlPrefix.trim();
    }

    public static NaruToolCall parseXmlLikeToolCall(String input) {
        NaruToolCall c = new NaruToolCall();
        // Find the function name first
        Matcher funcMatcher = Pattern.compile("<function=([^>]+)>").matcher(input);
        if (funcMatcher.find()) {
            c.setName(funcMatcher.group(1));
        } else {
            return null;
        }

        // Find all parameters
        Matcher paramMatcher = Pattern.compile("<parameter=([^>]+)>(.*?)</parameter>", Pattern.DOTALL).matcher(input);
        while (paramMatcher.find()) {
            c.getArguments().put(paramMatcher.group(1), paramMatcher.group(2).trim());
        }
        return c;
    }

    protected String url(NaruTask task, Map<String, NElement> env) {
        String url = task.session().agent().env().get(configPrefix + ".url").flatMap(x -> x.asStringValue()).orElse("http://localhost:11434");
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    protected NDuration connectTimeout(NaruTask task, Map<String, NElement> env) {
        return task.session().agent().env().get(configPrefix + ".connectTimeout").flatMap(x -> x.asStringValue())
                .flatMap(x -> NDuration.parse(x))
                .orElseGetOptionalFrom(
                        () -> task.session().agent().env().get(configPrefix + ".timeout").flatMap(x -> x.asStringValue())
                                .flatMap(x -> NDuration.parse(x))
                )
                .orElseGet(() -> {
                    return NDuration.ofSeconds(120);
                });
    }

    protected NDuration readTimeout(NaruTask task, Map<String, NElement> env) {
        return task.session().agent().env().get(configPrefix + ".readTimeout").flatMap(x -> x.asStringValue())
                .flatMap(x -> NDuration.parse(x))
                .orElseGetOptionalFrom(
                        () -> task.session().agent().env().get(configPrefix + ".timeout").flatMap(x -> x.asStringValue())
                                .flatMap(x -> NDuration.parse(x))
                )
                .orElse(NDuration.ofSeconds(120));
    }

    @Override
    public NaruResponse chat(NaruModelRequest mrequest, NaruTask task) {
        Map<String, NElement> env = mrequest.env();
        boolean toolsWrapped = false;
        boolean emulate_tool_calls = false;
        if (mrequest.env().get("emulate_tool_calls") != null && mrequest.env().get("emulate_tool_calls").isBoolean()) {
            emulate_tool_calls = mrequest.env().get("emulate_tool_calls").asBooleanValue().get();
        }
        if (!capabilities.isTools() && !mrequest.tools().isEmpty() || emulate_tool_calls) {
            mrequest = NoTollWrapHelper.wrapRequest(mrequest, NoTollWrapHelper.TOOL_CALL_SEP, NoTollWrapHelper.TOOL_RESULT_SEP);
            toolsWrapped = true;
        }
        NElement body = serializer.serialize(mrequest, model, task.session());
        NWebCli http = NWebCli.of()
                .connectTimeout(connectTimeout(task, env))
                .baseUri(url(task, env));
        NWebRequest request = http.POST(chatPath)
                .timeout(readTimeout(task, env))
                .jsonRequestBody(body);

        try {
            NChronometer chrono = NChronometer.of();
            NaruUtils.logWebRequest(request, NMsg.ofC("chat with %s", model), body);
            NWebResponse response = request.run().ifErrorThrow();
            String responseString = response.contentAsString();
            NElement responseElement = NElementReader.ofJson().read(responseString);
            NaruUtils.logWebResponse(request, NMsg.ofC("chat with %s", model), body, responseElement, chrono);
            NaruResponse naruResponse = parseResponse(responseString);
            if (toolsWrapped || emulate_tool_calls) {
                naruResponse = NoTollWrapHelper.unwrapResponse(naruResponse, NoTollWrapHelper.TOOL_CALL_SEP, task);
            }
            return naruResponse;
        } catch (Exception e) {
            throw new NIllegalArgumentException(NMsg.ofC("Failed to communicate with Ollama at %s: %s", request.effectiveUri(), e.getMessage(), e));
        }
    }


    // ── Response parser ────────────────────────────────────────────────────────

    protected NaruResponse parseResponse(String json) {
        return nElementReader.read(json, NaruResponse.class);
    }


    @Override
    public NaruModelCapabilities getCapabilities() {
        return capabilities;
    }


}
