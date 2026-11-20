package net.thevpc.naru.examples;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.examples.support.Examples;
import net.thevpc.naru.examples.support.ScriptedModelProvider;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NText;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Example 2 — the LangChain role: prompt templates, a sequential chain, and a tool.
 *
 * <p>LangChain's building blocks map onto NARU like this:
 * <ul>
 *   <li><b>PromptTemplate</b> — rendered in Java; inputs can also be handed to the task
 *       as variables ({@link NaruTaskSpec#var}) for directives to use.</li>
 *   <li><b>SequentialChain / LCEL pipe</b> — the task's statements, run in order;
 *       {@code /set x = _} keeps a step's answer ({@code _} is the last result, and every
 *       statement replaces it, so capture it on the very next line).</li>
 *   <li><b>Memory</b> — a task <i>is</i> a conversation: each step sees the earlier ones.</li>
 *   <li><b>Tool / function calling</b> — a {@link DefaultNaruTool}; the runtime runs the
 *       call-tool-answer loop, no AgentExecutor to wire.</li>
 *   <li><b>Output</b> — read back from Java with {@link NaruTask#getTaskEnv}.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q exec:java -Dexample=net.thevpc.naru.examples.Ex02Chain}
 * (add {@code -Dnaru.model=ollama/qwen2.5-coder:7b} for a real model).
 */
public class Ex02Chain {

    /** A tool is a name, a JSON-schema definition and a Java method. */
    static class LookupOrderTool extends DefaultNaruTool {
        LookupOrderTool() {
            // no tags: tools are fail-closed, so an untagged tool must say it is essential to be
            // offered. A real tool would carry tags (fs, network, write...) that tasks are granted.
            super("lookup_order", new String[0]);
        }

        @Override
        public boolean isEssential() {
            return true;
        }

        @Override
        public NText getDescription(NaruTask task) {
            return NText.ofPlain("Return the shipping status of an order.");
        }

        @Override
        public NaruToolDefinition getDefinition(NaruTask task) {
            return new NaruToolDefinitionFunction(name(), getDescription(task),
                    NaruToolParameter.string("order_id", "The order id, e.g. A-1042", true).build());
        }

        @Override
        public String execute(NaruToolCallContext context) {
            String id = context.stringArg("order_id").orElse("?").strip();
            // your database / REST call goes here
            return "A-1042".equalsIgnoreCase(id)
                    ? "order A-1042: shipped 2026-11-02 via DHL, last scan at Tunis hub 9 days ago"
                    : "order " + id + ": not found";
        }
    }

    /** Toolsets are how tools reach a session; register one, or ship it via META-INF/services. */
    static class OrderToolsetProvider implements NaruToolsetProvider {
        @Override
        public String name() {
            return "orders";
        }

        @Override
        public List<String> supportedTypes() {
            return List.of("orders");
        }

        @Override
        public NaruToolset createToolset(String id, NObjectElement config) {
            return new DefaultNaruToolset(id, List.of(new LookupOrderTool()));
        }
    }

    public static void main(String[] args) {
        // boots nuts first: NElement and the other nuts types need a running workspace
        NaruWorkspace ws = Examples.workspace("chain");
        scriptOfflineAnswers();
        NaruSession session = Examples.session(ws);
        session.registry().registerToolsetProvider(new OrderToolsetProvider());

        String ticket = "Hi, my package for order A-1042 never arrived and it has been two weeks. Can you check?";

        // A prompt template is rendered in Java. (Prompt lines are sent verbatim: NARU expands
        // $vars in directives such as /set and /print, not in text addressed to the model.)
        String classify = "Classify this support ticket as BILLING, SHIPPING or OTHER. "
                + "Reply with the label only. Ticket: %s";

        NaruTask chain = session.newTask(NaruTaskSpec.of()
                .name("support-chain")
                .var("ticket", NElement.ofString(ticket))
                .statements(
                        // step 1: classify
                        classify.formatted(ticket),
                        "/set category = _",
                        // step 2: extract; the ticket is already in the conversation (memory)
                        "Extract the order id from the ticket. Reply with the id only.",
                        "/set order_id = _",
                        // step 3: tool use; the model decides to call lookup_order
                        "Use the lookup_order tool to check that order, then state its status in one sentence.",
                        "/set status = _",
                        // step 4: compose from everything above
                        "Write a short, friendly reply to the customer (two sentences).",
                        "/set reply = _",
                        // steps can also compute without the model: /set evaluates expressions
                        "/set summary = \"[\" + category + \"] \" + order_id + \": \" + status"
                ));
        chain.setModel(Examples.model());

        session.start();
        chain.unhold();
        if (!chain.await(Duration.ofMinutes(5))) {
            throw new IllegalStateException("chain did not finish");
        }
        chain.throwIfFailed();

        System.out.println("\n== chain outputs, read from Java ==");
        // /set writes the task env; that is where Java reads a chain's outputs
        for (String key : List.of("category", "order_id", "status", "reply", "summary")) {
            System.out.println(key + " = " + chain.getTaskEnv(key, false)
                    .flatMap(NElement::asStringValue).orElse("<unset>"));
        }
        session.stop();
        System.exit(0);
    }

    private static void scriptOfflineAnswers() {
        ScriptedModelProvider.when("classify this support ticket", "SHIPPING");
        ScriptedModelProvider.when("extract the order id", "A-1042");
        ScriptedModelProvider.callTool("use the lookup_order tool", "lookup_order",
                Map.of("order_id", NElement.ofString("A-1042")),
                result -> "Order A-1042 shipped on 2 November but has not moved from the Tunis hub for 9 days.");
        ScriptedModelProvider.when("reply to the customer",
                "Sorry for the wait! Your order A-1042 has been stuck at our Tunis hub, and we have opened a trace with DHL today.");
    }
}
