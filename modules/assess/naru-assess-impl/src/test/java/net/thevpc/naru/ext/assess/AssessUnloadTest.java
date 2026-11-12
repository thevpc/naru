package net.thevpc.naru.ext.assess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * After a task the model must leave memory: Ollama keeps a model loaded for minutes by default,
 * and an assessment that walks several multi-gigabyte models would otherwise hold them all at once.
 */
class AssessUnloadTest {
    @Test
    void unloadAsksOllamaToDropTheModelImmediately() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            boolean ok = AssessRunner.unloadOllamaModel("ollama/qwen3-coder:30b", "http://127.0.0.1:" + server.getAddress().getPort());
            assertTrue(ok);
            assertEquals("/api/generate", path.get());
            assertTrue(body.get().contains("\"model\":\"qwen3-coder:30b\""), body.get());
            assertTrue(body.get().contains("\"keep_alive\":0"), body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonOllamaModelsAreLeftAlone() {
        assertFalse(AssessRunner.unloadOllamaModel("openai/gpt-4o", "http://127.0.0.1:1"));
    }

    @Test
    void anUnreachableServerIsNotAnError() {
        assertFalse(AssessRunner.unloadOllamaModel("ollama/x:1b", "http://127.0.0.1:1"));
    }
}
