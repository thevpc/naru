package net.thevpc.naru.examples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.thevpc.naru.api.agent.NaruInput;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionHandle;
import net.thevpc.naru.api.agent.NaruTurnResult;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruScriptedModelProvider;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.examples.support.Examples;
import net.thevpc.nuts.ext.NExtensions;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Example 4 — NARU embedded in a server: one turn per request, asleep in between.
 *
 * <p>A chat backend should not keep an agent thread per user. With a
 * {@link NaruSessionHandle} it does not have to:
 * <ol>
 *   <li>a request arrives for conversation {@code id};</li>
 *   <li>{@code buildHandle()} is cheap — nothing is loaded yet;</li>
 *   <li>{@code submit(input)} wakes the session: it is created, or reloaded from the
 *       {@code NaruSessionStore} with its history, tasks and variables;</li>
 *   <li>the task answers, then parks waiting for the next line. A session whose every task
 *       waits for input is idle, so the handle <b>hibernates</b> it: saved to the store and
 *       dropped from memory. The turn's future completes with {@code HIBERNATED};</li>
 *   <li>the next request, possibly minutes later or on another server instance sharing the
 *       store, wakes it again.</li>
 * </ol>
 *
 * <p>The store here is the file store under the workspace folder; a backend would pass its
 * own ({@code .store(...)}, e.g. the JDBC module) so any instance can serve any conversation.
 *
 * <p>Run: {@code mvn -q exec:java -Dexample=net.thevpc.naru.examples.Ex04TurnServer}. It
 * starts the server, sends itself four requests from two users, and exits; add
 * {@code -Dserve=true} to keep serving and try
 * {@code curl -d 'Hi, my name is Ada' localhost:8765/chat/alice}.
 */
public class Ex04TurnServer {

    private final NaruWorkspace ws;

    Ex04TurnServer(NaruWorkspace ws) {
        this.ws = ws;
    }

    /** One HTTP request = one turn. Nothing about the conversation lives in this object. */
    String turn(String conversationId, String text) throws Exception {
        TurnCapture capture = new TurnCapture();
        NaruSessionHandle handle = ws.newSession()
                .id(conversationId)               // stable id: the store resolves it
                .interactive()                    // a conversation: park on input after each turn
                .task(NaruTaskSpec.of().name("chat").model(Examples.modelKey()))
                .interaction(capture)             // where this turn's output goes
                .hibernateAfter(Duration.ZERO)    // sleep as soon as the turn is done
                // .store(myJdbcStore)            // share conversations between instances
                // .listener(event -> sse.send(event)) // stream chunks/tool calls to a browser
                .buildHandle();
        NaruTurnResult result = handle.submit(NaruInput.of(text)).result().get(5, TimeUnit.MINUTES);
        if (result.failure() != null) {
            throw new IllegalStateException(result.failure());
        }
        return capture.answer() + "   [" + result.status() + "]";
    }

    /** Collects what the model said during one turn. A real host would stream it instead. */
    static class TurnCapture implements NaruInteraction {
        private final StringBuilder answer = new StringBuilder();

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
            // the handle answers input requests from submit(); nothing to do here
        }

        @Override
        public synchronized void write(NaruLogMode mode, NMsg message) {
            // AGENT_RESPONSE also carries engine notices ("Loading agent from ..."), so a chat
            // answer is MODEL_RESPONSE only
            if (mode == NaruLogMode.MODEL_RESPONSE) {
                answer.append(NText.of(message).filteredText());
            }
        }

        @Override
        public synchronized void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            if (mode == NaruLogMode.MODEL_RESPONSE) {
                answer.append(NText.of(fragment).filteredText());
            }
        }

        @Override
        public void close() {
        }

        synchronized String answer() {
            return answer.toString().strip();
        }
    }

    public static void main(String[] args) throws Exception {
        NaruWorkspace ws = Examples.workspace("turn-server");
        // a nuts component instance, so it also reaches the sessions the handle rebuilds from
        // the store on each turn
        NExtensions.of().registerInstance(NaruModelProvider.class, offlineModel());
        Ex04TurnServer backend = new Ex04TurnServer(ws);

        int port = Integer.getInteger("port", 8765);
        HttpServer http = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/chat/", exchange -> {
            String id = exchange.getRequestURI().getPath().substring("/chat/".length());
            String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                reply(exchange, 200, backend.turn(id, text));
            } catch (Exception e) {
                reply(exchange, 500, String.valueOf(e));
            }
        });
        http.start();
        System.out.println("listening on http://localhost:" + port + "/chat/{conversation}");

        // Two users, interleaved. Every request finds its conversation asleep in the store.
        HttpClient client = HttpClient.newHttpClient();
        for (String[] call : List.of(
                new String[]{"alice", "Hi, my name is Ada."},
                new String[]{"bob", "Hi, my name is Linus."},
                new String[]{"alice", "What is my name?"},
                new String[]{"bob", "What is my name?"})) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/chat/" + call[0]))
                    .POST(HttpRequest.BodyPublishers.ofString(call[1])).build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println(call[0] + " > " + call[1]);
            System.out.println(call[0] + " < " + r.body());
        }

        NPath sessions = ws.projectDirectory().resolve(".naru/local/sessions");
        System.out.println("\nconversations at rest in the store: "
                + sessions.list().stream().map(NPath::name).toList());

        if (!Boolean.getBoolean("serve")) {
            http.stop(0);
            System.exit(0);
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Offline stand-ins. The second answer only works if the first turn survived hibernation. */
    private static NaruScriptedModelProvider offlineModel() {
        Pattern intro = Pattern.compile("my name is (\\w+)", Pattern.CASE_INSENSITIVE);
        return new NaruScriptedModelProvider()
                .when("what is my name", r -> {
                    for (NaruMessage m : r.messages()) {
                        if (m.getRole() == NaruRole.user && m.getFilteredContent() != null) {
                            Matcher matcher = intro.matcher(m.getFilteredContent());
                            if (matcher.find()) {
                                return NaruMessage.assistant("Your name is " + matcher.group(1) + ".");
                            }
                        }
                    }
                    return NaruMessage.assistant("I don't know your name yet.");
                })
                .when("my name is", r -> {
                    Matcher matcher = intro.matcher(r.messages().get(r.messages().size() - 1).getFilteredContent());
                    return NaruMessage.assistant(matcher.find() ? "Nice to meet you, " + matcher.group(1) + "!" : "Hello!");
                });
    }
}
