package net.thevpc.naru.ext.models;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.ext.models.util.NaruModelUtils;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.net.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.mon.NChronometer;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;
import java.util.UUID;
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
                .flatMap(x -> NDuration.of(x))
                .orElseGetOptionalFrom(
                        () -> task.session().agent().env().get(configPrefix + ".timeout").flatMap(x -> x.asStringValue())
                                .flatMap(x -> NDuration.of(x))
                )
                .orElseGet(() -> {
                    return NDuration.ofSeconds(120);
                });
    }

    protected NDuration readTimeout(NaruTask task, Map<String, NElement> env) {
        return task.session().agent().env().get(configPrefix + ".readTimeout").flatMap(x -> x.asStringValue())
                .flatMap(x -> NDuration.of(x))
                .orElseGetOptionalFrom(
                        () -> task.session().agent().env().get(configPrefix + ".timeout").flatMap(x -> x.asStringValue())
                                .flatMap(x -> NDuration.of(x))
                )
                .orElse(NDuration.ofSeconds(120));
    }

    protected NaruModelRequest preprocessRequest(NaruModelRequest mrequest, NaruTask task) {
        boolean emulate_tool_calls = false;
        if (mrequest.env().get("emulate_tool_calls") != null && mrequest.env().get("emulate_tool_calls").isBoolean()) {
            emulate_tool_calls = mrequest.env().get("emulate_tool_calls").asBooleanValue().get();
        }
        if (!capabilities.isTools() && !mrequest.tools().isEmpty() || emulate_tool_calls) {
            mrequest = NoToolWrapHelper.wrapRequest(mrequest, NoToolWrapHelper.TOOL_CALL_SEP, NoToolWrapHelper.TOOL_RESULT_SEP);
        }
        return mrequest;
    }

    protected void prepareRequest(NHttpRequest request, NElement body, NaruTask task) {
        // Subclasses can inject headers, auth, etc.
    }

    protected void onResponseReceived(NHttpResponse response, NaruTask task) {
        // Subclasses can inspect headers, track telemetry, etc.
    }

    protected int maxRetries(NaruTask task, Map<String, NElement> env) {
        if (task != null && task.session() != null) {
            NOptional<NElement> opt = task.session().agent().env().get(configPrefix + ".maxRetries");
            if (opt.isPresent()) {
                return opt.get().asIntValue().orElse(5);
            }
            opt = task.session().agent().env().get("model.maxRetries");
            if (opt.isPresent()) {
                return opt.get().asIntValue().orElse(5);
            }
        }
        return 5;
    }

    protected NDuration retryPeriod(NaruTask task, Map<String, NElement> env) {
        if (task != null && task.session() != null) {
            NOptional<NElement> opt = task.session().agent().env().get(configPrefix + ".retryPeriod");
            if (opt.isPresent()) {
                return opt.get().asStringValue().flatMap(NDuration::of).orElse(NDuration.ofSeconds(2));
            }
            opt = task.session().agent().env().get("model.retryPeriod");
            if (opt.isPresent()) {
                return opt.get().asStringValue().flatMap(NDuration::of).orElse(NDuration.ofSeconds(2));
            }
        }
        return NDuration.ofSeconds(2);
    }

    public static class NonRetryableWebException extends Error {
        private final String responseString;

        public NonRetryableWebException(Throwable cause, String responseString) {
            super(cause);
            this.responseString = responseString;
        }

        public String getResponseString() {
            return responseString;
        }
    }

    @Override
    public NaruResponse chat(NaruModelRequest mrequest, NaruTask task) {
        Map<String, NElement> env = mrequest.env();
        boolean toolsWrapped = (!capabilities.isTools() && !mrequest.tools().isEmpty());
        boolean emulate_tool_calls = mrequest.env().get("emulate_tool_calls") != null
                && mrequest.env().get("emulate_tool_calls").isBoolean()
                && mrequest.env().get("emulate_tool_calls").asBooleanValue().get();

        NaruModelRequest preparedModelRequest = preprocessRequest(mrequest, task);
        NElement body = serializer.serialize(preparedModelRequest, model, task.session());
        NHttpClient http = NHttpClient.of()
                .connectTimeout(connectTimeout(task, env))
                .baseUri(url(task, env));
        NHttpRequest request = http.POST(chatPath)
                .timeout(readTimeout(task, env))
                .jsonRequestBody(body);
        prepareRequest(request, body, task);

        int maxRetries = maxRetries(task, env);
        NDuration baseDelay = retryPeriod(task, env);
        java.util.concurrent.atomic.AtomicReference<NDuration> dynamicRetryAfter = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger attemptCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        try(net.thevpc.nuts.concurrent.NRetryCall<NaruResponse> retryCall = net.thevpc.nuts.concurrent.NRetryCall
                .of("llm-" + provider().name()+"-"+ UUID.randomUUID(), () -> {
            int attempt = attemptCounter.incrementAndGet();
            NChronometer chrono = NChronometer.of();
            java.time.Instant reqTime = java.time.Instant.now();
            NHttpResponse response = null;
            String responseString = null;
            Throwable error = null;
            try {
                NaruModelUtils.logWebRequest(request, NMsg.ofC("chat with %s (attempt %s)", model, attempt), body);
                response = request.run();
                NHttpCode code = response.statusCode();

                if (code.equals(NHttpCode.TOO_MANY_REQUESTS) ) {
                    NDuration retryAfter = NaruModelUtils.parseRetryAfter(response);
                    if (retryAfter != null) {
                        dynamicRetryAfter.set(retryAfter);
                    }
                    responseString = response.contentAsString();
                    throw new NHttpResponseException(
                            NMsg.ofC("Rate limit exceeded (HTTP 429) from %s: %s", provider().name(), response.statusMessage()),
                            null,
                            response.statusCode()
                    );
                } else if (response.isClientError()) {
                    // Fatal 4xx error (e.g. 400, 401, 403, 404) -> Do not retry
                    responseString = response.contentAsString();
                    throw new NonRetryableWebException(new NHttpResponseException(
                            NMsg.ofC("Client error (HTTP %s) from %s: %s", code, provider().name(), response.statusMessage()),
                            null,
                            response.statusCode()
                    ), responseString);
                } else if (response.isError()) {
                    // 5xx error or other error -> if 502/503/504 retryable, else retryable up to maxRetries
                    NDuration retryAfter = NaruModelUtils.parseRetryAfter(response);
                    if (retryAfter != null) {
                        dynamicRetryAfter.set(retryAfter);
                    }
                    responseString = response.contentAsString();
                    response.ifErrorThrow();
                }

                responseString = response.contentAsString();
                onResponseReceived(response, task);
                NElement responseElement = null;
                try {
                    responseElement = NElementReader.ofJson().read(responseString);
                } catch (Exception ignored) {
                }
                NaruModelUtils.logWebResponse(request, NMsg.ofC("chat with %s (attempt %s)", model, attempt), body, responseElement != null ? responseElement : responseString, chrono);
                NaruResponse naruResponse = parseResponse(responseString);
                if (toolsWrapped || emulate_tool_calls) {
                    naruResponse = NoToolWrapHelper.unwrapResponse(naruResponse, NoToolWrapHelper.TOOL_CALL_SEP, task);
                }
                return naruResponse;
            } catch (Throwable t) {
                error = t instanceof NonRetryableWebException ? ((NonRetryableWebException) t).getCause() : t;
                throw t;
            } finally {
                NaruModelUtils.logAudit(
                        task,
                        task != null ? task.session() : null,
                        provider().name(),
                        model.model(),
                        request,
                        body,
                        response,
                        responseString,
                        error,
                        attempt,
                        chrono.duration(),
                        reqTime
                );
            }
        })){
            retryCall.maxRetries(maxRetries)
                    .retryPeriod(attempt -> {
                        NDuration custom = dynamicRetryAfter.getAndSet(null);
                        if (custom != null && !custom.isZero()) {
                            return custom;
                        }
                        // Exponential backoff: baseDelay * 2^(attempt - 1)
                        return baseDelay.mul(Math.pow(2.0, Math.max(0, attempt - 1)));
                    });

            try {
                return retryCall.call();
            } catch (NonRetryableWebException nre) {
                Throwable cause = nre.getCause();
                net.thevpc.nuts.log.NLog.of(getClass()).log(NMsg.ofC("Failed to communicate with %s at %s: %s\n-----BODY\n%s\n-----BODY\n-----RESPONSE\n%s\n-----RESPONSE",
                        provider().name(), request.effectiveUri(), cause.getMessage(),
                        NElementWriter.ofTson().formatPlain(body),
                        nre.getResponseString()
                ).asError());
                throw new NIllegalArgumentException(NMsg.ofC("Failed to communicate with %s at %s: %s", provider().name(), request.effectiveUri(), cause.getMessage(), cause));
            } catch (Exception e) {
                net.thevpc.nuts.log.NLog.of(getClass()).log(NMsg.ofC("Failed to communicate with %s at %s: %s\n-----BODY\n%s\n-----BODY",
                        provider().name(), request.effectiveUri(), e.getMessage(),
                        NElementWriter.ofTson().formatPlain(body)
                ).asError());
                throw new NIllegalArgumentException(NMsg.ofC("Failed to communicate with %s at %s: %s", provider().name(), request.effectiveUri(), e.getMessage(), e));
            }
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
