package net.thevpc.naru.ext.tools.ollama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.text.NMsg;
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
import java.util.function.Consumer;

/**
 * Regression tests for {@code /ollama pull}.
 *
 * <p>Ollama's {@code /api/pull} is a streaming NDJSON endpoint: it answers
 * {@code 200} immediately and reports progress -- and failures -- inside the
 * event stream. The original implementation only checked the status code and
 * never read the body, so it declared success before (and without) the download
 * and silently ignored in-band errors. These tests drive the consumer from a
 * real HTTP response so a body that is not actually consumed cannot pass.
 */
public class OllamaPullStreamTest {

    private HttpServer server;

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
    }

    private NHttpResponse postPull(String baseUrl) {
        return NHttpClient.of().baseUri(baseUrl)
                .POST("api/pull")
                .jsonRequestBody(java.util.Map.of("model", "qwen2.5-coder:7b"))
                .run();
    }

    private String startServer(Consumer<HttpExchange> handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/pull", handler::accept);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    public void progressIsStreamedAndSuccessReportedOnlyAtTheEnd() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        CountDownLatch progressSeen = new CountDownLatch(1);
        AtomicBoolean finishTimedOut = new AtomicBoolean();

        String baseUrl = startServer(exchange -> {
            try {
                String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requestBodies.add(req);
                exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("{\"status\":\"pulling manifest\"}\n".getBytes(StandardCharsets.UTF_8));
                    out.write("{\"status\":\"downloading\",\"completed\":50,\"total\":100}\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    // the terminator is only sent once the client has consumed
                    // an earlier event, so a buffering client deadlocks here
                    if (!progressSeen.await(10, TimeUnit.SECONDS)) {
                        finishTimedOut.set(true);
                    }
                    out.write("{\"status\":\"success\"}\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });

        List<String> logs = new ArrayList<>();
        Consumer<NMsg> logger = msg -> {
            logs.add(msg.toString());
            if (logs.size() >= 1) {
                progressSeen.countDown();
            }
        };

        OllamaService.of().consumePullStream(postPull(baseUrl), "qwen2.5-coder:7b", logger);

        Assertions.assertFalse(finishTimedOut.get(),
                "the server never saw an event before finishing: the pull body was buffered, not streamed");
        Assertions.assertEquals(1, requestBodies.size());
        Assertions.assertTrue(requestBodies.get(0).contains("qwen2.5-coder:7b"),
                "the pull request must name the model to fetch");
        Assertions.assertTrue(logs.stream().anyMatch(l -> l.contains("pulling manifest")),
                "progress events must be surfaced");
        Assertions.assertTrue(logs.get(logs.size() - 1).contains("pulled successfully"),
                "success must only be announced after the terminal success event");
    }

    @Test
    public void inBandErrorInTheStreamIsReported() throws Exception {
        String baseUrl = startServer(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("{\"status\":\"pulling manifest\"}\n".getBytes(StandardCharsets.UTF_8));
                    out.write("{\"error\":\"model 'nope' not found\"}\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> OllamaService.of().consumePullStream(postPull(baseUrl), "nope", null));
        Assertions.assertTrue(ex.getMessage().contains("model 'nope' not found"),
                "the in-band error must be surfaced, not swallowed; got: " + ex.getMessage());
    }

    @Test
    public void streamEndingWithoutSuccessIsAnError() throws Exception {
        String baseUrl = startServer(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("{\"status\":\"pulling manifest\"}\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> OllamaService.of().consumePullStream(postPull(baseUrl), "m", null));
        Assertions.assertTrue(ex.getMessage().contains("before reporting success"), ex.getMessage());
    }

    @Test
    public void httpErrorStatusIsReported() throws Exception {
        String baseUrl = startServer(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "bad request".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> OllamaService.of().consumePullStream(postPull(baseUrl), "m", null));
        Assertions.assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
    }
}
