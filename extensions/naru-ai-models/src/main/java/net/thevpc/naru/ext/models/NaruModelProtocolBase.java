package net.thevpc.naru.ext.models;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.ext.models.cache.NaruModelCaching;
import net.thevpc.naru.ext.models.stream.NaruStreamResponseParser;
import net.thevpc.naru.ext.models.util.NaruModelUtils;
import net.thevpc.nuts.concurrent.NCallable;
import net.thevpc.nuts.concurrent.NRetryCall;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.log.NLog;
import net.thevpc.nuts.net.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.mon.NChronometer;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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


    public NaruModelProtocolBase(NaruModelProvider provider, NaruModelConfig model, String configPrefix,
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

    protected String apiKey(NaruTask task) {
        // design §6: the instance's own value (post $NAME) → layered env (session →
        // agent → system) under <instance id>.apiKey → the type's default env keys.
        // provider.apiKey() *is* that chain, and reading it first is the one ordering
        // change §6 requires: a registration's value must beat the env key of the
        // same name, not lose to it.
        String k = provider == null ? null
                : provider.apiKey(task.session()).map(NStringUtils::stripToNull).orNull();
        if (k != null) {
            return k;
        }
        // legacy: a protocol built with a configPrefix that differs from the provider's
        // own name keeps resolving <configPrefix>.apiKey from env
        return configValue(task, "apiKey").orNull();
    }

    protected String apiKeyConfigKey() {
        return configPrefix + ".apiKey";
    }

    /**
     * Config resolution for any wire-layer key (design §6): the provider instance's
     * own value — {@code $NAME} references resolved per request against the layered
     * env (session → agent → system), so a rotated export needs no re-registration —
     * then that layered env under {@code <configPrefix>.<key>}. Empty when neither
     * is set (or a {@code $NAME} references an unset variable): an unresolved
     * reference is not a value, the caller falls through to its default.
     */
    protected NOptional<String> configValue(NaruTask task, String key) {
        if (provider != null) {
            String raw = provider.rawParam(key).orNull();
            if (!NBlankable.isBlank(raw)) {
                NOptional<String> resolved = NaruModelRegistration.interpolate(raw,
                        NaruModelRegistration.envResolver(task == null ? null : task.session()));
                if (resolved.isPresent() && !NBlankable.isBlank(resolved.get())) {
                    return NOptional.of(NStringUtils.strip(resolved.get()));
                }
            }
        }
        if (task != null && task.session() != null) {
            String env = NaruModelRegistration.envValue(task.session(), configPrefix + "." + key);
            if (!NBlankable.isBlank(env)) {
                return NOptional.of(env);
            }
        }
        return NOptional.ofEmpty();
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
        String url = configValue(task, "url").orElse("http://localhost:11434");
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * The endpoint to POST to, relative to the base url.
     *
     * <p>Overridable because some wire formats put the model in the path
     * ({@code models/gemini-2.5-pro:generateContent}) rather than in the body,
     * which a fixed path cannot express.
     */
    protected String chatPath(NaruTask task, Map<String, NElement> env) {
        return chatPath;
    }

    protected NDuration connectTimeout(NaruTask task, Map<String, NElement> env) {
        return configValue(task, "connectTimeout").flatMap(x -> NDuration.of(x))
                .orElseGetOptionalFrom(() -> configValue(task, "timeout").flatMap(x -> NDuration.of(x)))
                .orElseGet(() -> {
                    return NDuration.ofSeconds(120);
                });
    }

    protected NDuration readTimeout(NaruTask task, Map<String, NElement> env) {
        return configValue(task, "readTimeout").flatMap(x -> NDuration.of(x))
                .orElseGetOptionalFrom(() -> configValue(task, "timeout").flatMap(x -> NDuration.of(x)))
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
        return applyThinking(mrequest, task);
    }

    /**
     * Publish an explicit {@code model.thinking} into the request env.
     *
     * <p>Here rather than in each protocol because the setting is provider-neutral
     * while its wire spelling is not: Ollama wants {@code think}, Anthropic-style
     * endpoints want a budget object, and most OpenAI-compatible servers want
     * nothing at all. Resolving it once means a protocol that has a spelling reads
     * one key instead of re-deriving the setting chain, and a protocol with no
     * spelling costs nothing.
     *
     * <p>Only an explicitly configured value is published. Absent stays absent, so a
     * protocol with no default of its own keeps its current behaviour instead of
     * inheriting an invented "off".
     */
    protected NaruModelRequest applyThinking(NaruModelRequest mrequest, NaruTask task) {
        NOptional<Boolean> thinking = NaruThinkingConfig.find(task);
        if (thinking == null || !thinking.isPresent()) {
            return mrequest;
        }
        Map<String, NElement> env = new HashMap<>(
                mrequest.env() == null ? Collections.emptyMap() : mrequest.env());
        env.put(NaruThinkingConfig.THINKING_KEY, NElement.ofBoolean(thinking.get()));
        return mrequest.withEnv(env);
    }

    /**
     * Classifies a response status into "proceed" or "this attempt failed".
     *
     * <p>Shared by the batched and the streamed path so that a 401, a 429 and a
     * retryable 503 mean the same thing either way. Duplicating this is how a
     * streaming client ends up retrying a 400 forever, or ignoring a 429 that the
     * batched path honours.
     *
     * <p>On a 5xx {@code failFast} is called so a retry does not have to drain
     * an error page before reusing the connection.
     *
     * @param dynamicRetryAfter collects a server-sent {@code Retry-After} when
     *                          one is present, so the retry policy can honour it
     * @return {@code null} when the response should be read normally, otherwise
     *         the error body for the caller to log and report
     */
    protected String classifyStatus(NHttpResponse response, AtomicReference<NDuration> dynamicRetryAfter) {
        if (response.statusCode().equals(NHttpCode.TOO_MANY_REQUESTS)) {
            NDuration retryAfter = NaruModelUtils.parseRetryAfter(response);
            if (retryAfter != null) {
                dynamicRetryAfter.set(retryAfter);
            }
            return response.contentAsString();
        }
        if (response.isClientError()) {
            // Fatal 4xx error (e.g. 400, 401, 403, 404) -> Do not retry
            return response.contentAsString();
        }
        if (response.isError()) {
            // 5xx error -> retryable, honouring Retry-After when the server sent one
            NDuration retryAfter = NaruModelUtils.parseRetryAfter(response);
            if (retryAfter != null) {
                dynamicRetryAfter.set(retryAfter);
            }
            response.failFast();
            return response.contentAsString();
        }
        return null;
    }

    protected void prepareRequest(NHttpRequest request, NElement body, NaruTask task) {
        // Subclasses can inject headers, auth, etc.
    }

    /**
     * Format the request body once and refuse to send an empty one.
     *
     * <p>An empty body used to reach the server and be rejected with a bare
     * {@code 400 missing request body}, which told the user nothing about which
     * model or provider was at fault. Failing here names both instead.
     */
    private byte[] formatRequestBody(NElement body) {
        String json = NElementWriter.ofJson().formatPlain(body);
        if (NBlankable.isBlank(json) || "null".equals(json.trim())) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "empty request body while calling %s (model %s)",
                    provider().name(), model.model()));
        }
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * (Re-)arm the HTTP body just before an attempt.
     *
     * <p>{@code NHttpRequest} stores its body as a <em>single-read</em> input
     * source, so the first {@code run()} consumes it. Retrying the very same
     * request would therefore transmit an empty body -- exactly the state that
     * made Ollama answer {@code 400 missing request body}. The serialised bytes
     * are immutable, so re-applying them before every attempt rebuilds a fresh
     * single-read source and guarantees each attempt sends the identical
     * payload, which is also what prompt-cache correctness depends on.
     */
    private static void armRequestBody(NHttpRequest request, byte[] jsonBody) {
        request.requestBody(jsonBody).contentType("application/json");
    }

    /**
     * Serialise with the cache plan when the provider's serializer understands
     * one, and otherwise fall back to the plain three-argument call.
     *
     * <p>Degrading silently rather than throwing is deliberate: a provider can
     * declare caching support while reusing a shared serializer that predates
     * it, and a user should get uncached-but-working behaviour instead of an
     * error. {@code NaruCachePlanView.none()} keeps the emitted body identical
     * to what a non-segmented request always produced.
     */
    protected NElement serialize(NaruModelRequest request, NaruCachePlanView plan, NaruTask task) {
        return serialize(request, plan, task, false);
    }

    /**
     * Serialise, adding the streaming flag when the body is going to be read
     * incrementally.
     */
    protected NElement serialize(NaruModelRequest request, NaruCachePlanView plan, NaruTask task, boolean stream) {
        if (plan != null && plan.mode() != NaruCachingMode.NONE && serializer instanceof NaruCacheAwareRequestSerializer) {
            return ((NaruCacheAwareRequestSerializer) serializer).serialize(request, model,
                    task == null ? null : task.session(), plan, stream);
        }
        return serializer.serialize(request, model, task == null ? null : task.session(), stream);
    }

    protected void onResponseReceived(NHttpResponse response, NaruTask task) {
        // Subclasses can inspect headers, track telemetry, etc.
    }

    protected int maxRetries(NaruTask task, Map<String, NElement> env) {
        if (task != null && task.session() != null) {
            NOptional<String> own = configValue(task, "maxRetries");
            if (own.isPresent()) {
                return NLiteral.of(own.get()).asInt().orElse(5);
            }
            String global = NaruModelRegistration.envValue(task.session(), "model.maxRetries");
            if (!NBlankable.isBlank(global)) {
                return NLiteral.of(global).asInt().orElse(5);
            }
        }
        return 5;
    }

    protected NDuration retryPeriod(NaruTask task, Map<String, NElement> env) {
        if (task != null && task.session() != null) {
            NOptional<NDuration> own = configValue(task, "retryPeriod").flatMap(NDuration::of);
            if (own.isPresent()) {
                return own.get();
            }
            String global = NaruModelRegistration.envValue(task.session(), "model.retryPeriod");
            if (!NBlankable.isBlank(global)) {
                return NDuration.of(global).orElse(NDuration.ofSeconds(2));
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

        // Plan the cache before serialising: the plan decides where breakpoints
        // land, so the serializer needs it. The body is built once and retried
        // verbatim, which is what we want — a retry must re-send the identical
        // prefix, not a freshly planned one that would invalidate itself.
        NaruCachingMode cachingMode = capabilities.cachingMode();
        final NaruCachePlanView cachePlan;
        if (cachingMode != null && cachingMode != NaruCachingMode.NONE) {
            cachePlan = NaruModelCaching.plan(task.session(), provider().name(), model.model(),
                    cachingMode, preparedModelRequest);
        } else {
            cachePlan = NaruCachePlanView.none();
        }
        NElement body = serialize(preparedModelRequest, cachePlan, task);
        byte[] jsonBody = formatRequestBody(body);
        NHttpClient http = NHttpClient.of()
                .connectTimeout(connectTimeout(task, env))
                .baseUri(url(task, env));
        NHttpRequest request = http.POST(chatPath(task, env))
                .timeout(readTimeout(task, env));
        armRequestBody(request, jsonBody);
        prepareRequest(request, body, task);

        int maxRetries = maxRetries(task, env);
        NDuration baseDelay = retryPeriod(task, env);
        AtomicReference<NDuration> dynamicRetryAfter = new AtomicReference<>();
        AtomicInteger attemptCounter = new AtomicInteger(0);
        NElement headersElements = NElement.of(request.headers());
        try (NRetryCall<NaruResponse> retryCall = NRetryCall.of("llm-" + provider().name() + "-" + UUID.randomUUID(), new NaruProtocolChatWebCall(attemptCounter, request, body, jsonBody, dynamicRetryAfter, task, toolsWrapped, emulate_tool_calls, preparedModelRequest, cachePlan, cachingMode))) {
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
            } catch (Exception e) {
                throw createAndLogWebError(request.effectiveUri(),headersElements,body,e);
            }
        }
    }

    private RuntimeException createAndLogWebError(String effectiveUri, NElement headersElements, NElement body, Throwable error){
        Throwable cause=error;
        String responseString=null;
        if(error instanceof NonRetryableWebException) {
            cause = error.getCause();
            responseString=((NonRetryableWebException)error).getResponseString();
        }
        NLog.of(getClass()).log(NMsg.ofC("Failed to communicate with %s at %s: %s\n-----HEADERS\n%s\n-----HEADERS\n-----BODY\n%s\n-----BODY%s",
                provider().name(), effectiveUri, cause.getMessage(),
                NElementWriter.ofTson().formatPlain(headersElements),
                NElementWriter.ofTson().formatPlain(body),
                responseString==null?"":
                        NMsg.ofC("\n-----RESPONSE\n%s\n-----RESPONSE",responseString)
        ).asError());
        return new NIllegalArgumentException(NMsg.ofC("Failed to communicate with %s at %s: %s", provider().name(), effectiveUri, cause.getMessage(), cause));
    }
    // ── Streaming ──────────────────────────────────────────────────────────────

    @Override
    public String providerName() {
        return provider == null ? null : provider.name();
    }

    /**
     * Runs a streamed call, retrying only while nothing has been delivered.
     *
     * <p>Retrying a stream that has already emitted tokens would deliver them
     * twice: the renderer has already drawn them and the transcript has already
     * recorded them, and there is no way to un-send bytes to a third party. So a
     * retry is allowed only while the attempt produced no chunk at all, which is
     * exactly the case a retry fixes -- a connection refused, a 503 before the
     * first token. Once anything has been delivered, the failure is reported.
     *
     * @param parser consumes events in order and assembles the response
     */
    protected NaruResponse streamChat(NaruModelRequest mrequest, NaruTask task,
                                      NaruStreamResponseParser parser) {
        Map<String, NElement> env = mrequest.env();
        NaruModelRequest preparedModelRequest = preprocessRequest(mrequest, task);

        NaruCachingMode cachingMode = capabilities.cachingMode();
        final NaruCachePlanView cachePlan;
        if (cachingMode != null && cachingMode != NaruCachingMode.NONE) {
            cachePlan = NaruModelCaching.plan(task.session(), provider().name(), model.model(),
                    cachingMode, preparedModelRequest);
        } else {
            cachePlan = NaruCachePlanView.none();
        }
        // The body is built once and retried verbatim, exactly as in the batched
        // path: a retry must re-send the identical prefix, or it invalidates the
        // very cache state it is relying on.
        NElement body = serialize(preparedModelRequest, cachePlan, task, true);
        byte[] jsonBody = formatRequestBody(body);
        NHttpClient http = NHttpClient.of()
                .connectTimeout(connectTimeout(task, env))
                .baseUri(url(task, env));
        NHttpRequest request = http.POST(chatPath(task, env))
                .timeout(readTimeout(task, env))
                // Without this some servers buffer the whole event stream before
                // flushing, and "streaming" degrades into a slow batch call.
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache");
        armRequestBody(request, jsonBody);
        prepareRequest(request, body, task);

        int maxRetries = maxRetries(task, env);
        NDuration baseDelay = retryPeriod(task, env);
        AtomicReference<NDuration> dynamicRetryAfter = new AtomicReference<>();
        NElement headersElements = NElement.of(request.headers());
        int attempt = 0;
        for (; ; ) {
            attempt++;
            NHttpResponse response = null;
            Throwable error = null;
            try {
                NaruModelUtils.logWebRequest(request, NMsg.ofC("stream chat with %s (attempt %s)", model, attempt), body);
                armRequestBody(request, jsonBody);
                response = request.run();
                String errorBody = classifyStatus(response, dynamicRetryAfter);
                if (errorBody != null) {
                    if (response.isClientError()) {
                        throw new NonRetryableWebException(new NHttpResponseException(
                                NMsg.ofC("Client error (HTTP %s) from %s: %s", response.statusCode(),
                                        provider().name(), response.statusMessage()),
                                null, response.statusCode()), errorBody);
                    }
                    throw new NHttpResponseException(
                            NMsg.ofC("Server error (HTTP %s) from %s: %s", response.statusCode(),
                                    provider().name(), response.statusMessage()),
                            null, response.statusCode());
                }
                onResponseReceived(response, task);
                NaruResponse result = parser.read(response);
                commitCacheState(preparedModelRequest, cachePlan, cachingMode, task);
                return result;
            } catch (NonRetryableWebException nre) {
                throw createAndLogWebError(request.effectiveUri(),headersElements,body,nre);
            } catch (Exception e) {
                error = e;
                // Nothing delivered yet means the user saw nothing, so a retry is
                // invisible to them; anything delivered and the failure stands.
                if (!parser.hasDeliveredContent() && attempt <= maxRetries) {
                    NDuration wait = dynamicRetryAfter.getAndSet(null);
                    if (wait == null || wait.isZero()) {
                        wait = baseDelay.mul(Math.pow(2.0, Math.max(0, attempt - 1)));
                    }
                    NLog.of(getClass()).log(NMsg.ofC(
                            "Stream from %s failed before any output (attempt %s of %s): %s",
                            provider().name(), attempt, maxRetries, e.getMessage()));
                    try {
                        Thread.sleep(Math.max(0L, wait.toMillis()));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new NIllegalArgumentException(NMsg.ofC("Stream from %s was cancelled", provider().name()));
                    }
                    continue;
                }
                throw createAndLogWebError(request.effectiveUri(),headersElements,body,e);
            } finally {
                NaruModelUtils.logAudit(task, task != null ? task.session() : null,
                        provider().name(), model.model(), request, body, response,
                        null, error, attempt, NDuration.ofSeconds(0), java.time.Instant.now());
            }
        }
    }


    // ── Response parser ────────────────────────────────────────────────────────

    /**
     * Remember the prefix this turn actually sent.
     *
     * <p>Overridable so a provider with a stateful cache can record the server's
     * resource id instead. Any failure here is swallowed: losing a cache hint
     * costs tokens, and must never cost the response the user is waiting for.
     */
    protected void commitCacheState(NaruModelRequest request, NaruCachePlanView plan,
                                    NaruCachingMode mode, NaruTask task) {
        if (mode == null || mode == NaruCachingMode.NONE || plan == null || task == null) {
            return;
        }
        try {
            NaruModelCaching.commit(task.session(), provider().name(), model.model(), mode,
                    request.cacheableContext());
        } catch (RuntimeException ex) {
            NLog.of(getClass()).log(NMsg.ofC("Could not record prompt cache state: %s", ex.getMessage()));
        }
    }

    protected NaruResponse parseResponse(String json) {
        return nElementReader.read(json, NaruResponse.class);
    }


    @Override
    public NaruModelCapabilities getCapabilities() {
        return capabilities;
    }

    private class NaruProtocolChatWebCall implements NCallable<NaruResponse> {
        private final AtomicInteger attemptCounter;
        private final NHttpRequest request;
        private final NElement body;
        private final byte[] jsonBody;
        private final AtomicReference<NDuration> dynamicRetryAfter;
        private final NaruTask task;
        private final boolean toolsWrapped;
        private final boolean emulate_tool_calls;
        private final NaruModelRequest preparedModelRequest;
        private final NaruCachePlanView cachePlan;
        private final NaruCachingMode cachingMode;

        public NaruProtocolChatWebCall(AtomicInteger attemptCounter, NHttpRequest request, NElement body, byte[] jsonBody, AtomicReference<NDuration> dynamicRetryAfter, NaruTask task, boolean toolsWrapped, boolean emulate_tool_calls, NaruModelRequest preparedModelRequest, NaruCachePlanView cachePlan, NaruCachingMode cachingMode) {
            this.attemptCounter = attemptCounter;
            this.request = request;
            this.body = body;
            this.jsonBody = jsonBody;
            this.dynamicRetryAfter = dynamicRetryAfter;
            this.task = task;
            this.toolsWrapped = toolsWrapped;
            this.emulate_tool_calls = emulate_tool_calls;
            this.preparedModelRequest = preparedModelRequest;
            this.cachePlan = cachePlan;
            this.cachingMode = cachingMode;
        }

        @Override
        public NaruResponse call() {
            int attempt = attemptCounter.incrementAndGet();
            NChronometer chrono = NChronometer.of();
            java.time.Instant reqTime = java.time.Instant.now();
            NHttpResponse response = null;
            String responseString = null;
            Throwable error = null;
            try {
                NaruModelUtils.logWebRequest(request, NMsg.ofC("chat with %s (attempt %s)", model, attempt), body);
                armRequestBody(request, jsonBody);
                response = request.run();
                NHttpCode code = response.statusCode();

                String errorBody = NaruModelProtocolBase.this.classifyStatus(response, dynamicRetryAfter);
                if (errorBody != null) {
                    responseString = errorBody;
                    if (response.isClientError()) {
                        throw new NonRetryableWebException(new NHttpResponseException(
                                NMsg.ofC("Client error (HTTP %s) from %s: %s", code, NaruModelProtocolBase.this.provider().name(), response.statusMessage()),
                                null,
                                response.statusCode()
                        ), responseString);
                    }
                    // 429 or a retryable 5xx
                    throw new NHttpResponseException(
                            NMsg.ofC("%s from %s: %s",
                                    code.equals(NHttpCode.TOO_MANY_REQUESTS)
                                            ? "Rate limit exceeded (HTTP 429)"
                                            : "Server error (HTTP " + code + ")",
                                    NaruModelProtocolBase.this.provider().name(), response.statusMessage()),
                            null,
                            response.statusCode()
                    );
                }

                responseString = response.contentAsString();
                NaruModelProtocolBase.this.onResponseReceived(response, task);
                NElement responseElement = null;
                try {
                    responseElement = NElementReader.ofJson().read(responseString);
                } catch (Exception ignored) {
                }
                NaruModelUtils.logWebResponse(request, NMsg.ofC("chat with %s (attempt %s)", model, attempt), body, responseElement != null ? responseElement : responseString, chrono);
                NaruResponse naruResponse = NaruModelProtocolBase.this.parseResponse(responseString);
                if (toolsWrapped || emulate_tool_calls) {
                    naruResponse = NoToolWrapHelper.unwrapResponse(naruResponse, NoToolWrapHelper.TOOL_CALL_SEP, task);
                }
                // Only now that the provider has accepted the request is the
                // prefix genuinely stored. Committing earlier — say, right after
                // serialising — would record a prefix that a failed call never
                // cached, and the next turn would trust a lie.
                NaruModelProtocolBase.this.commitCacheState(preparedModelRequest, cachePlan, cachingMode, task);
                return naruResponse;
            } catch (Throwable t) {
                error = t instanceof NonRetryableWebException ? t.getCause() : t;
                throw t;
            } finally {
                NaruModelUtils.logAudit(
                        task,
                        task != null ? task.session() : null,
                        NaruModelProtocolBase.this.provider().name(),
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
        }
    }
}
