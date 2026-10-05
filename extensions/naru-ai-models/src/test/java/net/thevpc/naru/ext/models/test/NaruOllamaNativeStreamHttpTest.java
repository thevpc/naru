package net.thevpc.naru.ext.models.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.thevpc.naru.api.model.NaruChunkKind;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruStreamCollector;
import net.thevpc.naru.ext.models.ollama.NaruOllamaNativeStreamParser;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives the Ollama stream parser from a real HTTP response instead of a
 * hand-fed string.
 *
 * <p>This exists to test the one thing a unit test cannot: that chunks are
 * delivered while the response is still open. Every other test could pass with a
 * body that is fully buffered before the first line is parsed, which is
 * indistinguishable from true streaming until a user sits in front of a REPL
 * watching a blank terminal. The server here refuses to finish until the client
 * has acknowledged the first document, so a buffering client deadlocks instead
 * of quietly passing.
 */
public class NaruOllamaNativeStreamHttpTest {

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

    /**
     * Starts a server that emits {@code documents} in order, waiting for
     * {@code beforeFinish} to be released before sending the last one.
     */
    private String startServer(List<String> documents, CountDownLatch beforeFinish,
                              AtomicBoolean finishTimedOut) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/chat", (HttpExchange exchange) -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            receivedBodies.add(new String(requestBody, StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < documents.size(); i++) {
                    out.write((documents.get(i) + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (i == documents.size() - 2) {
                        // the next line is the terminator: only send it once the
                        // client proves it has already consumed an earlier one
                        if (!beforeFinish.await(10, TimeUnit.SECONDS)) {
                            finishTimedOut.set(true);
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    public void documentsArriveOneChunkAtATimeOverRealHttp() throws Exception {
        CountDownLatch firstChunkSeen = new CountDownLatch(1);
        AtomicBoolean serverTimedOut = new AtomicBoolean();
        String baseUrl = startServer(List.of(
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"done\":false}",
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"lo\"},\"done\":false}",
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"!\"},\"done\":true,"
                        + "\"done_reason\":\"stop\",\"prompt_eval_count\":5,\"eval_count\":3}"),
                firstChunkSeen, serverTimedOut);

        NaruStreamCollector collector = new NaruStreamCollector();
        collector.addDelegate(chunk -> {
            if (chunk.kind() == NaruChunkKind.ANSWER) {
                firstChunkSeen.countDown();
            }
        });
        NaruOllamaNativeStreamParser parser = new NaruOllamaNativeStreamParser(
                "ollama", collector, new NaruModelConfig("m", "ollama"));

        NHttpClient http = NHttpClient.of().baseUri(baseUrl);
        NHttpResponse response = http.POST("/api/chat")
                .header("Accept", "application/x-ndjson")
                .header("Content-Type", "application/json")
                .requestBody("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}")
                .run();
        NaruResponse result = parser.read(response);

        Assertions.assertFalse(serverTimedOut.get(),
                "the server never saw a chunk before the response ended: the body was buffered, not streamed");
        Assertions.assertEquals(200, response.intStatusCode());
        Assertions.assertEquals("Hello!", result.getMessage().getContent());
        Assertions.assertEquals("stop", result.getStopReason());
        Assertions.assertEquals(5, result.getPromptTokens());
        Assertions.assertEquals(3, result.getEvalTokens());

        List<String> texts = new ArrayList<>();
        for (net.thevpc.naru.api.model.NaruStreamChunk chunk : collector.chunks()) {
            texts.add(chunk.text());
        }
        Assertions.assertEquals(List.of("Hel", "lo", "!"), texts,
                "documents must not be merged into one chunk");
    }

    @Test
    public void theRequestAsksForAStream() throws Exception {
        CountDownLatch firstChunkSeen = new CountDownLatch(1);
        AtomicBoolean serverTimedOut = new AtomicBoolean();
        String baseUrl = startServer(List.of(
                "{\"message\":{\"content\":\"a\"},\"done\":true}"),
                firstChunkSeen, serverTimedOut);

        NHttpClient http = NHttpClient.of().baseUri(baseUrl);
        NaruOllamaNativeStreamParser parser = new NaruOllamaNativeStreamParser(
                "ollama", new NaruStreamCollector(), new NaruModelConfig("m", "ollama"));
        NHttpResponse response = http.POST("/api/chat")
                .header("Accept", "application/x-ndjson")
                .requestBody("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}")
                .run();
        parser.read(response);

        Assertions.assertEquals(1, receivedBodies.size());
        Assertions.assertTrue(receivedBodies.get(0).contains("\"stream\":true"),
                "the provider must be asked to stream, or it will answer with a single batched document");
    }
}
