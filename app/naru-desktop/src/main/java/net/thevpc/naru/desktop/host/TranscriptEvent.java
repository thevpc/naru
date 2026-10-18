package net.thevpc.naru.desktop.host;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;

/**
 * The typed vocabulary a desktop host emits (plan §4.2).
 * <p>
 * Widgets consume only these; no widget ever sees {@code NaruLogMode} plumbing, ANSI, or a
 * backend class. That is what keeps {@code InteractionHost} and a future {@code TurnApiHost}
 * interchangeable: whichever produces these events, the transcript does not care.
 * <p>
 * Phase 0 implements the transcript + input subset. {@code ToolCall}, {@code TaskState},
 * {@code Usage} and {@code Session} events are added as their engine items land (E1, E3, E4).
 */
public sealed interface TranscriptEvent {

    /** A new renderable block begins. Everything after it belongs to {@code blockId} until {@link Closed}. */
    record Opened(String blockId, NaruLogMode mode) implements TranscriptEvent {
    }

    /**
     * Text appended to an open block. {@code finalFragment} marks the last fragment of a
     * streamed block, which is when a renderer may do the expensive one-shot work
     * (markdown parse, syntax highlight) instead of re-doing it per token.
     */
    record Chunk(String blockId, NaruLogMode mode, String text, boolean finalFragment) implements TranscriptEvent {
    }

    /** The block is complete and may be collapsed/frozen. */
    record Closed(String blockId) implements TranscriptEvent {
    }

    /**
     * A task is parked waiting for an answer. Carries the live request so the widget can call
     * {@link NaruInputRequest#deliver(String)} from the EDT. This event bypasses the coalescing
     * queue: losing it would strand a worker forever, so it is never dropped under load.
     */
    record InputRequested(NaruInputRequest request) implements TranscriptEvent {
    }

    /** The activation ended (hibernate or close). */
    record HostClosed(String reason) implements TranscriptEvent {
    }

    /**
     * Emission outran the queue and events were discarded. Every widget must handle this by
     * offering a resynchronisation from the durable store rather than showing a truncated
     * transcript as if it were complete.
     */
    record Lagged(long droppedEvents) implements TranscriptEvent {
    }
}
