package net.thevpc.naru.impl.ia.model.mistral;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.ia.model.openapi.NaruModelProtocolOpenAICompat;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.log.NLog;
import net.thevpc.nuts.net.NWebCli;
import net.thevpc.nuts.net.NWebRequest;
import net.thevpc.nuts.net.NWebResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NLiteral;

import java.time.Instant;
import java.util.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;


public class NaruModelProtocolMistral extends NaruModelProtocolOpenAICompat {
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern(
            "EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH
    );

    public NaruModelProtocolMistral(NaruModelProvider provider, NaruModelConfig model, String baseUrl, NaruModelCapabilities capabilities) {
        super(provider, model, baseUrl, "chat/completions", capabilities);
    }

    @Override
    public String url(NaruTask task, Map<String, NElement> env) {
        // Mistral's native endpoint
        String url = task.session().agent().env().get(configPrefix + ".url")
                .flatMap(x -> x.asStringValue())
                .orElse("https://api.mistral.ai/v1");
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
                        NMsg.ofC("Missing required Mistral API Key configuration: %s", apiKeyConfigKey())
                ));
        NaruModelRequest sanitizedRequest = sanitizeRequest(naruModelRequest);
        // Use the inherited robust OpenAI-compatible payload builder
        NElement body = serializer.serialize(sanitizedRequest, model, task.session());
        Map<String, NElement> env = sanitizedRequest.env();
        NWebCli http = NWebCli.of()
                .connectTimeout(connectTimeout(task, env))
                .baseUri(url(task, env));

        // Route calls using the configured bearer token header style
        NWebRequest request = http.POST(chatPath)
                .header("Authorization", "Bearer " + apiKey)
                .timeout(readTimeout(task, env))
                .jsonRequestBody(body);

        String responseString = null;
        try {
            NWebResponse response = request.run().ifErrorThrow();
            Map<String, String> headers = response.firstHeaders();
            Instant serverInstant = Instant.now();
            NDuration retryAfter = null;
            if (headers.containsKey("date")) {
                ZonedDateTime serverTime = ZonedDateTime.parse(headers.get("Date"), HTTP_DATE);
                serverInstant = serverTime.toInstant();
            }
            if (headers.get("retry-after") != null) {
                retryAfter = NLiteral.of(headers.get("retry-after")).asInt().map(NDuration::ofSeconds).orElse(null);
            }
            NObjectElementBuilder providerLastResultInfo = NElement.ofObjectBuilder();
            String correlationId = headers.get("mistral-correlation-id");
            providerLastResultInfo.set("correlation-id", correlationId);
            Integer tokensPerMinute = NLiteral.of(headers.get("x-ratelimit-limit-tokens-minute")).asInt().orElse(null);
            Integer remainingTokensPerMinute = NLiteral.of(headers.get("x-ratelimit-remaining-tokens-minute")).asInt().orElse(null);
            Integer reqPerMinute = NLiteral.of(headers.get("x-ratelimit-limit-req-minute")).asInt().orElse(null);
            Integer remainingReqPerMinute = NLiteral.of(headers.get("x-ratelimit-remaining-req-minute")).asInt().orElse(null);
            Integer tokensQueryCost = NLiteral.of(headers.get("x-ratelimit-tokens-query-cost")).asInt().orElse(null);
            Boolean maxRetryAttemptsReached = NLiteral.of(headers.get("x-max-retry-attempts-reached")).asBoolean().orElse(false);

            providerLastResultInfo.set("ratelimit-limit-tokens-minute", tokensPerMinute);
            providerLastResultInfo.set("ratelimit-remaining-tokens-minute", remainingTokensPerMinute);

            providerLastResultInfo.set("max-retry-attempts-reached", maxRetryAttemptsReached);

            providerLastResultInfo.set("ratelimit-tokens-query-cost", tokensQueryCost);


            providerLastResultInfo.set("ratelimit-limit-req-minute", reqPerMinute);
            providerLastResultInfo.set("ratelimit-remaining-req-minute", remainingReqPerMinute);


            providerLastResultInfo.set("retry-after", retryAfter == null ? null : retryAfter.toSeconds());

            task.session().meteringService().trackProviderStats(
                    new DefaultNaruProviderRateLimitInfo(
                            task.session().uuid(),
                            null,
                            provider().name(),
                            serverInstant,
                            List.of(
                                    new DefaultNaruRateLimitBucket(
                                            NaruRateLimitWindow.MINUTE,
                                            tokensPerMinute,
                                            remainingTokensPerMinute,
                                            null
                                    )
                            ),
                            List.of(
                                    new DefaultNaruRateLimitBucket(
                                            NaruRateLimitWindow.MINUTE,
                                            reqPerMinute,
                                            remainingReqPerMinute,
                                            null
                                    )
                            ),
                            correlationId,
                            retryAfter,
                            providerLastResultInfo.build()
                    ),
                    task.session()
            );
            responseString = response.contentAsString();
            return parseResponse(responseString);
        } catch (Exception e) {
            NLog.of(NaruModelProtocolMistral.class)
                    .log(
                            NMsg.ofC("Failed to communicate with Mistral at %s: %s\n-----BODY\n%s\n-----BODY\n-----RESPONSE\n%s\n-----RESPONSE",
                                    request.effectiveUri(), e.getMessage(), e,
                                    NElementWriter.ofJson().formatPlain(body),
                                    responseString
                            ).asError()
                    );
            throw new NIllegalArgumentException(
                    NMsg.ofC("Failed to communicate with Mistral at %s: %s", request.effectiveUri(), e.getMessage(), e)
            );
        }
    }

    /**
     * Sanitizes the request to ensure strict role alternation and a single combined system message.
     */
    private NaruModelRequest sanitizeRequest(NaruModelRequest originalRequest) {
        List<NaruMessage> rawMessages = originalRequest.messages();
        if (rawMessages == null || rawMessages.isEmpty()) {
            return originalRequest;
        }

        List<NaruMessage> normalized = new ArrayList<>();
        StringBuilder systemBuilder = new StringBuilder();
        NaruMessage lastMessage = null;

        for (NaruMessage msg : rawMessages) {
            NaruRole role = msg.getRole();
            String content = msg.getContent();

            // 1. Isolate and accumulate all system prompts regardless of where they appear
            if (role == NaruRole.system) {
                if (NBlankable.isNonBlank(content)) {
                    if (!systemBuilder.isEmpty()) {
                        systemBuilder.append("\n\n");
                    }
                    systemBuilder.append(content);
                }
                continue;
            }

            // 2. Process non-system turns (user, assistant, tool)
            // Skip tool execution blocks from simple text concatenation rules
            if (role == NaruRole.tool) {
                if (lastMessage != null) {
                    normalized.add(lastMessage);
                    lastMessage = null;
                }
                normalized.add(msg);
                continue;
            }

            if (lastMessage == null) {
                lastMessage = msg;
            } else if (lastMessage.getRole().equals(role)) {
                // Collapse consecutive identical roles (e.g., user + user)
                String mergedContent = lastMessage.getContent() + "\n\n" + content;
                lastMessage = lastMessage.withContent(mergedContent);
            } else {
                normalized.add(lastMessage);
                lastMessage = msg;
            }
        }

        if (lastMessage != null) {
            normalized.add(lastMessage);
        }

        // 3. Reconstruct the clean message list with a single system message at index 0
        List<NaruMessage> finalMessages = new ArrayList<>();
        if (!systemBuilder.isEmpty()) {
            // Re-use an existing message object style or its builder to maintain metadata
            NaruMessage baseSystem = rawMessages.stream()
                    .filter(m -> m.getRole() == NaruRole.system)
                    .findFirst()
                    .orElse(rawMessages.get(0));

            finalMessages.add(baseSystem.withContent(systemBuilder.toString()));
        }
        finalMessages.addAll(normalized);

        // Return a cloned/updated request object with the clean message wire-format
        return originalRequest.withMessages(finalMessages);
    }
}