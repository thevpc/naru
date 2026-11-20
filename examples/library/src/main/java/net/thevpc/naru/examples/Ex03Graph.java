package net.thevpc.naru.examples;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.examples.support.Examples;
import net.thevpc.naru.examples.support.ScriptedModelProvider;
import net.thevpc.nuts.elem.NElement;

import java.time.Duration;
import java.util.List;

/**
 * Example 3 — the LangGraph role: nodes, shared state, a conditional cycle, parallel branches.
 *
 * <pre>
 *            ┌──────────── REVISE (max 3 rounds) ───────────┐
 *            ▼                                              │
 *   START → writer ──────────────→ reviewer ──── APPROVED ──┴─→ ┬─→ seo ────┐
 *                                                               └─→ french ─┴─→ END
 * </pre>
 *
 * <p>How the LangGraph concepts map:
 * <ul>
 *   <li><b>Node</b> — a routine ({@code /use writer} + numbered lines). A node can call the
 *       model, run directives, call tools, or call other routines.</li>
 *   <li><b>State</b> — session variables ({@code /set --session x = ...}), visible to every
 *       task of the session and readable from Java ({@link NaruSession#getSessionEnv}).
 *       A session is saved and reloaded with them, which is NARU's checkpointing.</li>
 *   <li><b>Edges / conditional edges / cycles</b> — ordinary control flow: {@code /call},
 *       {@code /if}, {@code /while}, {@code /goto}.</li>
 *   <li><b>Parallel branches (fan-out / join)</b> — {@code /start} runs a node as its own task
 *       ({@code --fork} hands it a copy of the conversation); {@code /wait --for=children}
 *       is the join. A branch blocked on a slow model call blocks nothing else.</li>
 *   <li><b>Messages between nodes</b> — events: {@code /fire}, {@code /on}, {@code /wait --for=event(x)}
 *       (see examples/12-events.naru).</li>
 * </ul>
 *
 * <p>The graph is data — the same text could be loaded from a file, edited by the agent at
 * runtime, or typed in the NARU REPL.
 *
 * <p>Run: {@code mvn -q exec:java -Dexample=net.thevpc.naru.examples.Ex03Graph}
 */
public class Ex03Graph {

    static final String[] GRAPH = {
            // ── nodes ────────────────────────────────────────────────────────────────
            "/use writer",
            "10 /print \"-> writer (round $round)\"",
            "20 Write a two-sentence product description for Ferro, a reusable steel water bottle. If a reviewer asked for changes above, apply them. Reply with the description only.",
            "30 /set --session draft = _",

            "/use reviewer",
            "10 /print \"-> reviewer\"",
            "20 Review the latest description: it must mention the price, 29 EUR. Reply with exactly one word, APPROVED or REVISE.",
            "30 /set --session verdict = _",
            "40 /if verdict != \"APPROVED\"",
            "50 Give the writer one short instruction to fix the description.",
            "60 /end",

            "/use seo",
            "10 Give five comma-separated SEO keywords for the latest description. Reply with the keywords only.",
            "20 /set --session keywords = _",

            "/use french",
            "10 Translate the latest description into French. Reply with the translation only.",
            "20 /set --session french = _",

            // ── edges ────────────────────────────────────────────────────────────────
            "/routine main",
            "/set --session round = 0",
            "/set --session verdict = \"\"",
            "/while verdict != \"APPROVED\" && round < 3",       // conditional cycle
            "    /set --session round = round + 1",
            "    /call writer",
            "    /call reviewer",
            "/end",
            "/start --fork seo",                                // fan-out: two tasks in parallel
            "/start --fork french",
            "/wait --for=children",                             // join
            "/print \"graph finished after $round round(s), verdict=$verdict\"",
    };

    public static void main(String[] args) {
        NaruWorkspace ws = Examples.workspace("graph");
        scriptOfflineAnswers();

        NaruSession session = Examples.session(ws);
        NaruTask graph = session.newTask(NaruTaskSpec.of().name("graph").statements(GRAPH));
        graph.setModel(Examples.model());

        session.start();
        graph.unhold();
        if (!graph.await(Duration.ofMinutes(10))) {
            throw new IllegalStateException("graph did not finish");
        }
        graph.throwIfFailed();

        System.out.println("\n== final state, read from Java ==");
        for (String key : List.of("round", "verdict", "draft", "keywords", "french")) {
            System.out.println(key + " = " + session.getSessionEnv(key)
                    .map(e -> e.asStringValue().orElse(e.toString())).orElse("<unset>"));
        }
        // the session stopped by itself: its last task ended and nothing waits for input
        System.exit(0);
    }

    /** Offline stand-ins: the first draft forgets the price, so the cycle runs twice. */
    private static void scriptOfflineAnswers() {
        ScriptedModelProvider.when("product description for ferro", r -> NaruMessage.assistant(
                !lastAssistant(r).contains("Mention the price")
                        ? "Ferro keeps water cold for 24 hours. Its brushed steel body survives every commute."
                        : "Ferro keeps water cold for 24 hours. Built from brushed steel, it costs just 29 EUR."));
        ScriptedModelProvider.when("reply with exactly one word, approved or revise",
                r -> NaruMessage.assistant(lastAssistant(r).contains("29 EUR") ? "APPROVED" : "REVISE"));
        ScriptedModelProvider.when("one short instruction", "Mention the price, 29 EUR, in the second sentence.");
        ScriptedModelProvider.when("seo keywords", "steel water bottle, reusable bottle, insulated bottle, Ferro, eco bottle");
        ScriptedModelProvider.when("into french",
                "Ferro garde l'eau froide pendant 24 heures. En acier brossé, elle ne coûte que 29 EUR.");
    }

    private static String lastAssistant(NaruModelRequest r) {
        List<NaruMessage> m = r.messages();
        for (int i = m.size() - 1; i >= 0; i--) {
            if (m.get(i).getRole() == NaruRole.assistant && m.get(i).getFilteredContent() != null) {
                return m.get(i).getFilteredContent();
            }
        }
        return "";
    }
}
