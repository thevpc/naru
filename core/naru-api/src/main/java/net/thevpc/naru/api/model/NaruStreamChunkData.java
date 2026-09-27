package net.thevpc.naru.api.model;

import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

/**
 * The one {@link NaruStreamChunk} implementation.
 *
 * <p>Immutability is not incidental. A streaming reader hands chunks to a
 * renderer on a transport thread while the model call accumulates the same
 * chunks on a worker thread, so a mutable chunk could be seen half-updated by
 * both.
 */
public record NaruStreamChunkData(
        NaruChunkKind kind,
        String text,
        int index,
        String provider,
        NaruThinkingExtraction extraction,
        boolean complete
) implements NaruStreamChunk {

    public NaruStreamChunkData {
        if (kind == null) {
            throw new IllegalArgumentException("chunk kind is required");
        }
        // An empty chunk is legal -- a provider can legitimately stream an empty
        // delta -- so this normalises rather than rejects. What is not legal is
        // null, which would push the check onto every consumer instead.
        text = text == null ? "" : text;
    }

    public static NaruStreamChunkData of(NaruChunkKind kind, String text, int index) {
        return new NaruStreamChunkData(kind, text, index, null, null, true);
    }

    public static NaruStreamChunkData of(NaruChunkKind kind, String text, int index, String provider) {
        return new NaruStreamChunkData(kind, text, index, provider, null, true);
    }

    public static NaruStreamChunkData thinking(String text, int index, String provider,
                                               NaruThinkingExtraction extraction, boolean complete) {
        return new NaruStreamChunkData(NaruChunkKind.THINKING, text, index, provider, extraction, complete);
    }

    public static NaruStreamChunkData answer(String text, int index, String provider) {
        return new NaruStreamChunkData(NaruChunkKind.ANSWER, text, index, provider, null, true);
    }

    public NaruStreamChunkData withText(String newText) {
        return new NaruStreamChunkData(kind, newText, index, provider, extraction, complete);
    }

    public NaruStreamChunkData withIndex(int newIndex) {
        return new NaruStreamChunkData(kind, text, newIndex, provider, extraction, complete);
    }

    public NaruStreamChunkData asComplete(boolean newComplete) {
        return new NaruStreamChunkData(kind, text, index, provider, extraction, newComplete);
    }

    public NOptional<String> providerOptional() {
        return NOptional.ofNullable(provider);
    }

    public NOptional<NaruThinkingExtraction> extractionOptional() {
        return NOptional.ofNullable(extraction);
    }

    /**
     * The log mode this chunk should be rendered under, which is how a renderer
     * that carries no styling of its own -- a browser, an HTTP client -- can
     * still tell thinking from answer.
     */
    public net.thevpc.naru.api.agent.NaruLogMode logMode() {
        return kind == NaruChunkKind.THINKING
                ? net.thevpc.naru.api.agent.NaruLogMode.MODEL_THINKING
                : net.thevpc.naru.api.agent.NaruLogMode.MODEL_RESPONSE;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("chunk[").append(index).append(' ').append(kind);
        if (!NBlankable.isBlank(provider)) {
            sb.append(" via ").append(provider);
        }
        if (extraction != null) {
            sb.append(' ').append(extraction);
        }
        if (!complete) {
            sb.append(" TRUNCATED");
        }
        return sb.append(" ").append(text).append(']').toString();
    }
}
