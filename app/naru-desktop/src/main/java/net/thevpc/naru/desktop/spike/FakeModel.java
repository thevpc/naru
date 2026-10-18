package net.thevpc.naru.desktop.spike;

import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruChunkKind;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruStreamChunk;
import net.thevpc.naru.api.model.NaruStreamHandler;
import net.thevpc.naru.api.model.NaruThinkingExtraction;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;

import java.util.List;
import java.util.Set;

/**
 * A deterministic model that streams like a real one.
 * <p>
 * The Phase 0 measurements have to be repeatable and separable: whether the EDT survives a
 * slow turn is a Swing question, and a real 10-minute CPU prefill makes the test take ten
 * minutes and vary by host. So this emits exactly N chunks, X characters each, with a fixed
 * gap, and the real provider is run separately for realism.
 */
public class FakeModel {

    /**
     * @param chunks      number of streamed fragments
     * @param chunkChars  characters per fragment
     * @param gapMillis   pause between fragments (0 = as fast as the engine accepts)
     * @param thinking    emit a reasoning channel before the answer channel
     * @param tailDelayMs extra pause before the first chunk, standing in for prefill latency
     */
    public record Profile(int chunks, int chunkChars, long gapMillis, boolean thinking, long tailDelayMs) {

        public static Profile burst(int chunks) {
            return new Profile(chunks, 24, 0, false, 0);
        }

        /** ~60 s turn: 5 s "prefill", then 3000 chunks 18 ms apart. */
        public static Profile slowTurn() {
            return new Profile(3000, 20, 18, true, 5000);
        }

        /** High-rate abuse: everything the engine will take, as fast as it will take it. */
        public static Profile flood() {
            return new Profile(120_000, 30, 0, true, 0);
        }
    }

    private final Profile profile;
    private volatile int streamCalls;

    public FakeModel(Profile profile) {
        this.profile = profile;
    }

    public int streamCalls() {
        return streamCalls;
    }

    public NaruModelProvider provider() {
        return new NaruModelProvider() {
            @Override
            public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
                return NOptional.of(protocol());
            }

            @Override
            public String name() {
                return "fake";
            }

            @Override
            public NOptional<String> apiKey(NaruSession session) {
                return NOptional.ofEmpty();
            }

            @Override
            public List<String> findModelIds(NaruSession session) {
                return List.of("fake-model");
            }

            @Override
            public void setParam(String name, String value) {
            }

            @Override
            public NOptional<String> getParam(String name) {
                return NOptional.ofEmpty();
            }

            @Override
            public Set<String> getParamNames() {
                return Set.of();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void setEnabled(boolean enabled) {
            }
        };
    }

    public NaruModelProtocol protocol() {
        return new NaruModelProtocol() {
            @Override
            public String providerName() {
                return "fake";
            }

            @Override
            public NaruResponse chat(NaruModelRequest request, NaruTask task) {
                NaruResponse r = new NaruResponse();
                r.setMessage(NaruMessage.assistant("batched answer"));
                return r;
            }

            @Override
            public NaruResponse chatStream(NaruModelRequest request, NaruTask task, NaruStreamHandler handler) {
                streamCalls++;
                sleep(profile.tailDelayMs());
                if (profile.thinking()) {
                    emit(handler, NaruChunkKind.THINKING, "reasoning");
                }
                emit(handler, NaruChunkKind.ANSWER, "answer");
                NaruResponse r = new NaruResponse();
                r.setMessage(NaruMessage.assistant("done"));
                return r;
            }

            @Override
            public NaruModelCapabilities getCapabilities() {
                return new Caps(profile.thinking());
            }
        };
    }

    private void emit(NaruStreamHandler handler, NaruChunkKind kind, String label) {
        for (int i = 0; i < profile.chunks(); i++) {
            boolean last = i == profile.chunks() - 1;
            handler.onChunk(new Chunk(kind, payload(label, i, profile.chunkChars()), i, last));
            if (profile.gapMillis() > 0) {
                sleep(profile.gapMillis());
            }
        }
    }

    private static String payload(String label, int i, int chars) {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append('#').append(i).append(' ');
        while (sb.length() < chars) {
            sb.append('x');
        }
        return sb.toString();
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The streamed fragment the engine turns into {@code writeStream} calls. */
    record Chunk(NaruChunkKind kind, String text, int index, boolean complete) implements NaruStreamChunk {

        @Override
        public NaruChunkKind kind() {
            return kind;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public NaruThinkingExtraction extraction() {
            return NaruThinkingExtraction.NATIVE_FIELD;
        }

        @Override
        public boolean complete() {
            return complete;
        }
    }

    record Caps(boolean thinking) implements NaruModelCapabilities {

        @Override
        public long contextLength() {
            return 4096;
        }

        @Override
        public boolean isVision() {
            return false;
        }

        @Override
        public boolean isTools() {
            return false;
        }

        @Override
        public boolean isThinking() {
            return thinking;
        }

        @Override
        public boolean isEmbedding() {
            return false;
        }

        @Override
        public boolean isTextOnly() {
            return !thinking;
        }

        @Override
        public boolean isStreaming() {
            return true;
        }

        @Override
        public NaruCachingMode cachingMode() {
            return NaruCachingMode.NONE;
        }

        @Override
        public Set<String> keys() {
            return thinking ? Set.of("thinking") : Set.of("text-only");
        }

        @Override
        public NElement toElement() {
            return NElement.ofTupleBuilder("Capabilities")
                    .set("streaming", true)
                    .set("thinking", thinking)
                    .build();
        }
    }
}
