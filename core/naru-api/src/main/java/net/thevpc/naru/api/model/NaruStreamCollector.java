package net.thevpc.naru.api.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link NaruStreamHandler} that keeps what it is given.
 *
 * <p>Two jobs. It is how the model call reassembles the full answer while the
 * renderer is still being fed fragment by fragment, and it is how a
 * non-streaming provider is turned into a chunk sequence at all -- the whole
 * response is classified and pushed through this, so downstream sees the same
 * list it would have seen from a real stream.
 */
public class NaruStreamCollector implements NaruStreamHandler {

    private final List<NaruStreamChunk> chunks = new ArrayList<>();
    private final List<NaruStreamHandler> delegates = new ArrayList<>();
    private final BooleanSupplier cancelled;

    public NaruStreamCollector() {
        this(() -> false);
    }

    public NaruStreamCollector(BooleanSupplier cancelled) {
        this.cancelled = cancelled == null ? () -> false : cancelled;
    }

    /** Local stand-in for {@code BooleanSupplier}, to avoid a JDK-vs-android signature debate. */
    public interface BooleanSupplier {
        boolean get();
    }

    /**
     * Adds a handler to also feed, so rendering can happen while collection
     * happens without either knowing about the other.
     */
    public NaruStreamCollector addDelegate(NaruStreamHandler delegate) {
        if (delegate != null && delegate != this) {
            delegates.add(delegate);
        }
        return this;
    }

    @Override
    public void onChunk(NaruStreamChunk chunk) {
        if (chunk == null) {
            return;
        }
        chunks.add(chunk);
        for (NaruStreamHandler delegate : delegates) {
            delegate.onChunk(chunk);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    public List<NaruStreamChunk> chunks() {
        return List.copyOf(chunks);
    }

    public int size() {
        return chunks.size();
    }

    /**
     * All text of the given kind, concatenated. This is the reassembly step:
     * a streamed answer is many chunks and only their concatenation is the
     * answer.
     */
    public String text(NaruChunkKind kind) {
        StringBuilder sb = new StringBuilder();
        for (NaruStreamChunk chunk : chunks) {
            if (chunk.kind() == kind) {
                sb.append(chunk.text());
            }
        }
        return sb.toString();
    }

    public boolean has(NaruChunkKind kind) {
        for (NaruStreamChunk chunk : chunks) {
            if (chunk.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the last chunk of the given kind was cut short, which is how a
     * truncated thinking segment is recognised after the fact.
     */
    public boolean isTruncated(NaruChunkKind kind) {
        for (int i = chunks.size() - 1; i >= 0; i--) {
            NaruStreamChunk chunk = chunks.get(i);
            if (chunk.kind() == kind) {
                return !chunk.complete();
            }
        }
        return false;
    }

    public void clear() {
        chunks.clear();
    }
}
