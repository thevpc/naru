package net.thevpc.naru.examples;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.annotations.NaruArg;
import net.thevpc.naru.api.annotations.NaruToolSpec;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.examples.support.Examples;
import net.thevpc.naru.examples.support.ScriptedModelProvider;
import net.thevpc.nuts.elem.NElement;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Example 2 — the LangChain role: prompt templates, a sequential chain, and a tool.
 *
 * <p>LangChain's building blocks map onto NARU like this:
 * <ul>
 *   <li><b>PromptTemplate</b> — a prompt line naming variables, {@code $name} or
 *       {@code ${name}}; inputs come from Java ({@link NaruTaskSpec#var}) or earlier steps.</li>
 *   <li><b>SequentialChain / LCEL pipe</b> — the task's statements, run in order;
 *       {@code /set x = _} keeps a step's answer ({@code _} is the last result, and every
 *       statement replaces it, so capture it on the very next line).</li>
 *   <li><b>Memory</b> — a task <i>is</i> a conversation: each step sees the earlier ones.</li>
 *   <li><b>Tool / function calling</b> — an annotated method ({@link NaruToolSpec}); the
 *       runtime runs the call-tool-answer loop, no AgentExecutor to wire.</li>
 *   <li><b>Output</b> — read back from Java with {@link NaruTask#getTaskEnv}.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q exec:java -Dexample=net.thevpc.naru.examples.Ex02Chain}
 * (add {@code -Dnaru.model=ollama/qwen2.5-coder:7b} for a real model).
 */
public class Ex02Chain {

    /**
     * Tools are plain methods: the schema the model sees comes from the signature. Tags say what a
     * tool needs, and a task is offered it only if it holds one of them (the gate is fail-closed).
     * To have a class found on the classpath instead of registered, implement NaruToolContainer
     * and list it in META-INF/services/net.thevpc.nuts.spi.NComponent.
     */
    public static class OrderTools {
        @NaruToolSpec(description = "Return the shipping status of an order.", tags = "network")
        public static String lookupOrder(
                @NaruArg(name = "order_id", description = "The order id, e.g. A-1042") String orderId) {
            // your database / REST call goes here
            return "A-1042".equalsIgnoreCase(orderId.strip())
                    ? "order A-1042: shipped 2026-11-02 via DHL, last scan at Tunis hub 9 days ago"
                    : "order " + orderId + ": not found";
        }
    }

    public static void main(String[] args) {
        // boots nuts first: NElement and the other nuts types need a running workspace
        NaruWorkspace ws = Examples.workspace("chain");
        scriptOfflineAnswers();
        NaruSession session = Examples.session(ws);
        session.registry().registerToolsetProviderType(OrderTools.class);

        String ticket = "Hi, my package for order A-1042 never arrived and it has been two weeks. Can you check?";

        NaruTask chain = session.newTask(NaruTaskSpec.of()
                .name("support-chain")
                .addTags("network")   // lookup_order needs it; without it the model is never offered the tool
                .var("ticket", NElement.ofString(ticket))
                .statements(
                        // step 1: classify; ${ticket} is the variable handed over from Java
                        "Classify this support ticket as BILLING, SHIPPING or OTHER. Reply with the label only. Ticket: ${ticket}",
                        "/set category = _",
                        // step 2: extract; the ticket is already in the conversation (memory)
                        "Extract the order id from the ticket. Reply with the id only.",
                        "/set order_id = _",
                        // step 3: tool use; the model decides to call lookup_order
                        "Use the lookup_order tool to check that order, then state its status in one sentence.",
                        "/set status = _",
                        // step 4: compose, naming the earlier steps' results explicitly
                        "Write a short, friendly reply to the customer (two sentences) about their $category issue. Order status: $status",
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
