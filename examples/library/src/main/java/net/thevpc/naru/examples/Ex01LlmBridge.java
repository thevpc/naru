package net.thevpc.naru.examples;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruChunkKind;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.examples.support.Examples;
import net.thevpc.naru.examples.support.ScriptedModelProvider;

import java.util.List;
import java.util.Map;

/**
 * Example 1 — NARU as an LLM bridge.
 *
 * <p>The lowest layer: one Java API in front of every provider. No agent loop, no
 * scripting — build a request, get a response. Switching from a local Ollama model to
 * OpenAI, Anthropic, Gemini, Groq, Mistral, OpenRouter ... is a change of the
 * {@code provider/model} string, not of code.
 *
 * <p>Run offline: {@code mvn -q exec:java -Dexample=net.thevpc.naru.examples.Ex01LlmBridge}
 * <br>Run on Ollama: add {@code -Dnaru.model=ollama/qwen2.5-coder:7b}
 */
public class Ex01LlmBridge {
    public static void main(String[] args) {
        ScriptedModelProvider.when("capital of tunisia", "Tunis.");
        ScriptedModelProvider.when("haiku", "Old code wakes at dawn / one interface, many tongues / the JVM hums");

        NaruWorkspace ws = Examples.workspace("llm-bridge");
        NaruSession session = Examples.session(ws);

        // Every installed provider is already registered: they come from the classpath.
        System.out.println("providers: " + session.registry().modelProviders().keySet());

        // A model is (provider, model) plus sampling options.
        NaruModelConfig model = Examples.model()
                .withTemperature(0.2f)
                .withMaxTokens(200);
        NaruModelProtocol llm = session.registry().protocol(model, session).get();

        // Protocols take a task as their call context: it is where they read API keys from
        // the session env, retry settings, and where usage is reported. Built, never run.
        NaruTask context = session.newTask(NaruTaskSpec.of().name("bridge"));

        // 1. A plain completion.
        NaruResponse answer = llm.chat(new NaruModelRequest(List.of(
                NaruMessage.system("Answer in one short sentence."),
                NaruMessage.user("What is the capital of Tunisia?")
        ), Map.of()), context);
        System.out.println("\n== chat ==");
        System.out.println(answer.getMessage().getFilteredContent());
        System.out.println("tokens: prompt=" + answer.getPromptTokens() + " completion=" + answer.getEvalTokens());

        // 2. The same request shape, streamed. Providers that cannot stream still work:
        //    the default replays the full answer as chunks, so the handler is written once.
        System.out.println("\n== stream ==");
        llm.chatStream(new NaruModelRequest(List.of(
                NaruMessage.user("Write a haiku about the JVM. Reply with only the haiku.")
        ), Map.of()), context, chunk -> {
            if (chunk.kind() == NaruChunkKind.ANSWER) {
                System.out.print(chunk.text());
            }
        });
        System.out.println();

        // 3. Multi-turn is just a longer message list: the bridge holds no hidden state.
        NaruResponse followUp = llm.chat(new NaruModelRequest(List.of(
                NaruMessage.user("What is the capital of Tunisia?"),
                answer.getMessage(),
                NaruMessage.user("And roughly how many people live there? One sentence.")
        ), Map.of()), context);
        System.out.println("\n== follow-up ==");
        System.out.println(followUp.getMessage().getFilteredContent());

        System.exit(0);
    }
}
