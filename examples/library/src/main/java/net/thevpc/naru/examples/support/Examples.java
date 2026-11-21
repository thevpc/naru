package net.thevpc.naru.examples.support;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;

import java.util.EnumSet;
import java.util.Set;

/**
 * What every example shares: booting the runtime, choosing a model, and printing output.
 *
 * <p>The model comes from {@code -Dnaru.model=provider/model}. It defaults to the offline
 * {@link ScriptedModelProvider}, so the examples run anywhere; point it at a real model to
 * see the same code talk to one:
 * <pre>
 *   -Dnaru.model=ollama/qwen2.5-coder:7b
 *   -Dnaru.model=openai/gpt-4o-mini        (OPENAI_API_KEY in the environment)
 *   -Dnaru.model=anthropic/claude-haiku-5-5 (ANTHROPIC_API_KEY)
 * </pre>
 */
public final class Examples {
    private Examples() {
    }

    public static String modelKey() {
        return System.getProperty("naru.model", ScriptedModelProvider.NAME + "/demo");
    }

    public static boolean offline() {
        return modelKey().startsWith(ScriptedModelProvider.NAME + "/");
    }

    public static NaruModelConfig model() {
        NaruModelKey key = NaruModelKey.parse(modelKey()).get();
        return new NaruModelConfig(key.provider(), key.model());
    }

    /**
     * A workspace rooted in a scratch folder. {@link NaruWorkspace#of()} boots nuts (NARU's host
     * framework) when needed and takes the extensions already on the classpath, which is what a
     * library user wants: the pom decides what the agent can do.
     */
    public static NaruWorkspace workspace(String name) {
        NaruWorkspace ws = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-example-" + name);
        dir.mkdirs();
        ws.projectDirectory(dir);
        return ws;
    }

    /** A headless session: no terminal, no stdin thread, output printed by {@link Console}. */
    public static NaruSession session(NaruWorkspace ws) {
        return ws.newSession()
                .interaction(new Console())
                .build();
    }

    /**
     * The simplest {@link NaruInteraction}: print what a reader would want to see. A server
     * would push the same calls to a websocket or SSE stream instead.
     */
    public static class Console implements NaruInteraction {
        private final Set<NaruLogMode> shown;

        public Console() {
            this(EnumSet.of(NaruLogMode.AGENT_RESPONSE, NaruLogMode.MODEL_RESPONSE,
                    NaruLogMode.PROGRESS, NaruLogMode.RAW));
        }

        public Console(Set<NaruLogMode> shown) {
            this.shown = shown;
        }

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
            // headless: nobody to ask; the session handle example shows how input is supplied
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
            if (!shown.contains(mode)) {
                return;
            }
            // messages carry nuts style markup (##{p9:ollama}##) for terminals that
            // render it; filteredText() is the same message as plain text
            String text = NText.of(message).filteredText();
            // PROGRESS also announces every model call; keep the /print lines, drop that banner
            if (text.isBlank() || (mode == NaruLogMode.PROGRESS && text.startsWith("\uD83E\uDDE0"))) {
                return;
            }
            System.out.println("  [" + mode.name().toLowerCase() + "] " + text);
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            if (shown.contains(mode)) {
                System.out.print(NText.of(fragment).filteredText());
                if (end) {
                    System.out.println();
                }
            }
        }

        @Override
        public void close() {
        }
    }
}
