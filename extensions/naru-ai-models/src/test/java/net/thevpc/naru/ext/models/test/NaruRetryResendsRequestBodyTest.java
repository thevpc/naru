package net.thevpc.naru.ext.models.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.ollama.NaruModelProtocolOllamaNative;
import net.thevpc.naru.ext.models.ollama.NaruOllamaProvider;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A retried LLM call must resend the request body.
 *
 * <p>{@code NHttpRequest} holds its body as a <em>single-read</em> input source,
 * so the first {@code run()} consumes it. The protocol deliberately builds the
 * body once and retries "verbatim" -- but reusing the very same request meant
 * the second attempt transmitted an empty body. Ollama answered exactly that
 * with {@code 400 {"error":"missing request body"}}, a client error, which then
 * surfaced to the user instead of being retried.
 *
 * <p>The server here fails the first attempt with a retryable 503 and succeeds
 * on the next, so a retry is guaranteed. Every body it sees is asserted to be
 * non-empty and byte-identical, which is also what prompt-cache correctness
 * relies on. Without the fix the second body is empty and the test fails.
 */
public class NaruRetryResendsRequestBodyTest {

    private HttpServer server;
    private final List<String> receivedBodies = new ArrayList<>();

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        receivedBodies.clear();
    }

    private String startServer() throws Exception {
        return startServer(false);
    }

    private String startServer(boolean stream) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/chat", (HttpExchange exchange) -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            receivedBodies.add(new String(requestBody, StandardCharsets.UTF_8));
            if (calls.incrementAndGet() < 2) {
                // first attempt: a retryable server error
                byte[] err = "{\"error\":\"try again\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, err.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(err);
                }
            } else if (stream) {
                byte[] ok = ("{\"model\":\"qwen3\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":false}\n"
                        + "{\"model\":\"qwen3\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(ok);
                }
            } else {
                byte[] ok = ("{\"model\":\"qwen3\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                        + "\"done\":true,\"done_reason\":\"stop\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(ok);
                }
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    public void aRetriedCallSendsTheSameNonEmptyBody() throws Exception {
        String baseUrl = startServer();

        NaruModelProtocolOllamaNative protocol = protocol(baseUrl, false);
        NaruModelRequest request = new NaruModelRequest(
                List.of(NaruMessage.user("hi")), Collections.emptyList(), new HashMap<>());

        NaruResponse response = protocol.chat(request, (NaruTask) null);

        Assertions.assertEquals("ok", response.getMessage().getContent());
        assertEveryBodyWasNonEmptyAndIdentical();
    }

    @Test
    public void aRetriedStreamSendsTheSameNonEmptyBody() throws Exception {
        String baseUrl = startServer(true);

        NaruModelProtocolOllamaNative protocol = protocol(baseUrl, true);
        NaruModelRequest request = new NaruModelRequest(
                List.of(NaruMessage.user("hi")), Collections.emptyList(), new HashMap<>());

        NaruResponse response = protocol.chatStream(request, (NaruTask) null, null);

        Assertions.assertEquals("ok", response.getMessage().getContent());
        assertEveryBodyWasNonEmptyAndIdentical();
    }

    private void assertEveryBodyWasNonEmptyAndIdentical() {
        Assertions.assertTrue(receivedBodies.size() >= 2,
                "the server must have seen a retry, otherwise the bug is not exercised");
        for (int i = 0; i < receivedBodies.size(); i++) {
            Assertions.assertFalse(receivedBodies.get(i).isEmpty(),
                    "attempt " + (i + 1) + " sent an empty body");
        }
        Assertions.assertEquals(receivedBodies.get(0), receivedBodies.get(1),
                "a retry must resend the identical body, not a re-planned one");
    }

    private static NaruModelProtocolOllamaNative protocol(String baseUrl, boolean stream) {
        NaruOllamaProvider provider = new NaruOllamaProvider("ollama");
        provider.setParam("url", baseUrl);
        // make the retry immediate instead of the two-second default
        provider.setParam("maxRetries", "2");
        provider.setParam("retryPeriod", "1ms");
        provider.setEnabled(true);

        NaruModelCapabilities capabilities = new NaruModelCapabilitiesImpl(
                false, true, false, false, 8192, NaruCachingMode.NONE, stream);
        return new NaruModelProtocolOllamaNative(
                provider, new NaruModelConfig("ollama", "qwen3"), "ollama", capabilities);
    }
}
