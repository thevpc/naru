package net.thevpc.naru.ext.models.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.custom.NaruCustomProvider;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * End-to-end emulated tool calls: an OpenAI-compatible endpoint that answers a
 * model incapable of structured {@code tool_calls} with the call written inside
 * the emulated tag pair. The request env sets {@code emulate_tool_calls=true} —
 * which is exactly what a per-model {@code .naru/models/*.md} front matter
 * produces — and the protocol must turn the tagged block into a real tool call
 * in the batched <em>and</em> the streamed path, and honour custom tags.
 */
public class NaruEmulatedToolCallsTest {

    private static final String MODEL_ID = "m";
    private static final String EMPTY_TOOLS_BLOCK =
            "<|tool_call|>\n{\"tool\":\"bash\",\"args\":{\"command\":\"ls\"}}\n<|end_tool_call|>";
    private static final String CUSTOM_TOOLS_BLOCK =
            "<tool_call>\n{\"tool\":\"file_read\",\"args\":{\"path\":\"/x\"}}\n</tool_call>";

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

    @Test
    public void batchedEmulatedToolCallIsUnwrapped() throws Exception {
        String baseUrl = startServer(false, EMPTY_TOOLS_BLOCK);

        NaruResponse response = chat(baseUrl, new NaruModelRequest(
                List.of(NaruMessage.user("list the files")),
                Collections.emptyList(), envWith(false)));

        Assertions.assertTrue(response.hasToolCalls());
        Assertions.assertEquals("bash", response.getMessage().getToolCalls().get(0).getName());
        Assertions.assertEquals("ls", response.getMessage().getToolCalls().get(0).getArguments().get("command"));
        // the block was the whole answer, so the stripped content is empty
        Assertions.assertNull(response.getMessage().getContent(),
                "an emulated-only answer must come back with no leftover tag text");
    }

    @Test
    public void streamedEmulatedToolCallIsUnwrapped() throws Exception {
        String baseUrl = startServer(true, EMPTY_TOOLS_BLOCK);

        NaruResponse response = chatStream(baseUrl, new NaruModelRequest(
                List.of(NaruMessage.user("list the files")),
                Collections.emptyList(), envWith(false)));

        Assertions.assertTrue(response.hasToolCalls(),
                "the streamed emulated block must surface as a tool call, exactly like the batched path");
        Assertions.assertEquals("bash", response.getMessage().getToolCalls().get(0).getName());
        Assertions.assertEquals("ls", response.getMessage().getToolCalls().get(0).getArguments().get("command"));
        Assertions.assertNull(response.getMessage().getContent());
    }

    @Test
    public void streamedEmulatedToolCallHonoursConfiguredSeparators() throws Exception {
        String baseUrl = startServer(true, CUSTOM_TOOLS_BLOCK);

        NaruResponse response = chatStream(baseUrl, new NaruModelRequest(
                List.of(NaruMessage.user("read the file")),
                Collections.emptyList(), envWith(true)));

        Assertions.assertTrue(response.hasToolCalls());
        Assertions.assertEquals("file_read", response.getMessage().getToolCalls().get(0).getName());
        Assertions.assertEquals("/x", response.getMessage().getToolCalls().get(0).getArguments().get("path"));
    }

    @Test
    public void plainAnswerIsLeftAloneWhenEmulationIsOn() throws Exception {
        String baseUrl = startServer(false, "here is the file listing:\n- a\n- b");

        NaruResponse response = chat(baseUrl, new NaruModelRequest(
                List.of(NaruMessage.user("list the files")),
                Collections.emptyList(), envWith(false)));

        Assertions.assertFalse(response.hasToolCalls());
        Assertions.assertEquals("here is the file listing:\n- a\n- b", response.getMessage().getContent());
    }

    private NaruResponse chat(String baseUrl, NaruModelRequest request) {
        NaruSession session = createMockSession(Collections.emptyMap());
        return protocolFor(baseUrl, session).chat(request, mockTask(session));
    }

    private NaruResponse chatStream(String baseUrl, NaruModelRequest request) {
        NaruSession session = createMockSession(Collections.emptyMap());
        return protocolFor(baseUrl, session).chatStream(request, mockTask(session), null);
    }

    /** A custom OpenAI-compatible endpoint registered against the test server. */
    private NaruModelProtocol protocolFor(String baseUrl, NaruSession session) {
        NaruCustomProvider provider = (NaruCustomProvider) new NaruCustomProvider().newInstance("emu-openai");
        provider.setParam("url", baseUrl);
        provider.setParam("models", MODEL_ID);
        provider.setParam("probe", "false");
        provider.setParam("protocol", "openai");
        return provider.getProtocol(
                new NaruModelConfig("emu-openai", MODEL_ID), session).get();
    }

    /** A task whose session is the mock env; the chat path needs {@code session()}
     * (for the cache plan) and {@code id()} (for the audit trail) and nothing else. */
    private NaruTask mockTask(NaruSession session) {
        return (NaruTask) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "session":
                            return session;
                        case "id":
                            return 0L;
                        default:
                            return null;
                    }
                });
    }

    private Map<String, NElement> envWith(boolean customSeparators) {
        Map<String, NElement> env = new HashMap<>();
        env.put("emulate_tool_calls", NElement.of(true));
        if (customSeparators) {
            env.put("tool_call_start", NElement.ofString("<tool_call>"));
            env.put("tool_call_end", NElement.ofString("</tool_call>"));
        }
        return env;
    }

    private String startServer(boolean stream, String assistantContent) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/chat/completions", (HttpExchange exchange) -> {
            byte[] body;
            if (stream) {
                // split the block over several frames, as a real SSE response would
                String[] parts = assistantContent.equals(EMPTY_TOOLS_BLOCK)
                        ? new String[]{"<|tool_call|>", "{\"tool\":\"bash\",\"args\":{\"command\":\"ls\"}}", "<|end_tool_call|>"}
                        : assistantContent.equals(CUSTOM_TOOLS_BLOCK)
                        ? new String[]{"<tool_call>", "{\"tool\":\"file_read\",\"args\":{\"path\":\"/x\"}}", "</tool_call>"}
                        : new String[]{assistantContent};
                StringBuilder sb = new StringBuilder();
                for (String part : parts) {
                    sb.append("data: ").append("{\"choices\":[{\"delta\":{\"content\":")
                            .append(jsonString(part)).append("}}]}\n\n");
                }
                sb.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n");
                sb.append("data: [DONE]\n\n");
                body = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            } else {
                body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                        + jsonString(assistantContent) + "},\"finish_reason\":\"stop\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    private NaruSession createMockSession(Map<String, String> envMap) {
        NaruEnv env = new NaruEnv() {
            @Override
            public NOptional<NElement> get(String key) {
                String v = envMap.get(key);
                if (v == null) {
                    return NOptional.ofEmpty();
                }
                return NOptional.of(NElement.of(v));
            }

            @Override
            public NOptional<NElement> get(String key, NaruVisibility visibility) {
                return get(key);
            }

            @Override
            public void put(String key, NElement value, NaruVisibility visibility) {
            }
        };

        NaruAgent agent = (NaruAgent) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{NaruAgent.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("env".equals(m) || "agentEnv".equals(m)) {
                        return env;
                    }
                    return null;
                }
        );

        return (NaruSession) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "agent":
                            return agent;
                        case "getSessionEnv":
                        case "getProjectEnv":
                            return NOptional.ofEmpty();
                        default:
                            return null;
                    }
                }
        );
    }
}