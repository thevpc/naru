package net.thevpc.naru.examples;

import net.thevpc.naru.api.model.NaruChunkKind;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelContext;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruScriptedModelProvider;
import net.thevpc.naru.examples.support.Examples;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.ext.NServiceLoader;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Example 1 — NARU as an LLM bridge.
 *
 * <p>The lowest layer: one Java API in front of every provider. No agent loop, no
 * scripting, and no workspace or session either — build a request, get a response.
 * Switching from a local Ollama model to OpenAI, Anthropic, Gemini, Groq, Mistral,
 * OpenRouter ... is a change of the {@code provider/model} string, not of code.
 *
 * <p>A call runs in a {@link NaruModelContext}. Inside the engine it is the calling task's,
 * and inherits its settings; here it is {@link NaruModelContext#standalone()}: settings
 * from system properties, credentials from the environment (or the map given to it).
 *
 * <p>Run offline: {@code mvn -q exec:java -Dexample=net.thevpc.naru.examples.Ex01LlmBridge}
 * <br>Run on Ollama: add {@code -Dnaru.model=ollama/qwen2.5-coder:7b}
 */
public class Ex01LlmBridge {

    public static void main(String[] args) {
        // nuts is NARU's host framework; providers are nuts components found on the classpath
        Nuts.require();
        NaruScriptedModelProvider offline = new NaruScriptedModelProvider()
                .when("capital of tunisia", "Tunis.")
                .when("haiku", "Old code wakes at dawn / one interface, many tongues / the JVM hums");

        Map<String, NaruModelProvider> providers = new TreeMap<>();
        for (NaruModelProvider p : NServiceLoader.of(NaruModelProvider.class, null,
                Thread.currentThread().getContextClassLoader()).loadAll(null)) {
            providers.put(p.name(), p);
        }
        // a provider is a plain object: with no workspace there is no registry, so it is simply
        // used next to the ones found on the classpath
        providers.put(offline.name(), offline);
        System.out.println("providers: " + providers.keySet());

        // A model is (provider, model) plus sampling options.
        NaruModelConfig model = Examples.model()
                .withTemperature(0.2f)
                .withMaxTokens(200);
        // No workspace, no session. Pass API keys here instead of exporting them, e.g.
        // NaruModelContext.standalone(Map.of("OPENAI_API_KEY", key)).
        NaruModelContext context = NaruModelContext.standalone();
        NaruModelProtocol llm = providers.get(model.provider()).getProtocol(model, context).get();

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
