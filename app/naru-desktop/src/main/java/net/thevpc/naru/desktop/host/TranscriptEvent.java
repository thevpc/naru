package net.thevpc.naru.desktop.host;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruToolCallEvent;

/**
 * The typed vocabulary a desktop host emits (plan §4.2).
 * <p>
 * Widgets consume only these; no widget ever sees {@code NaruLogMode} plumbing, ANSI, or a
 * backend class. That is what keeps {@code InteractionHost} and a future {@code TurnApiHost}
 * interchangeable: whichever produces these events, the transcript does not care.
 * <p>
 * {@code TaskState}, {@code Usage} and {@code Session} events are still ahead of us: they land
 * with their engine items (E3, E4). {@link ToolCall} and the {@code taskId} on text events landed
 * with E1, which is why text rows can now say which task wrote them instead of the host guessing.
 */
public sealed interface TranscriptEvent {

    /** A new renderable block begins. Everything after it belongs to {@code blockId} until {@link Closed}. */
    record Opened(String blockId, NaruLogMode mode, long taskId) implements TranscriptEvent {

        /** Output that the engine did not attribute to a task. */
        public Opened(String blockId, NaruLogMode mode) {
            this(blockId, mode, NaruOutput.NO_TASK);
        }
    }

    /**
     * Text appended to an open block. {@code finalFragment} marks the last fragment of a
     * streamed block, which is when a renderer may do the expensive one-shot work
     * (markdown parse, syntax highlight) instead of re-doing it per token.
     * <p>
     * {@code taskId} is {@link NaruOutput#NO_TASK} unless the engine stamped the line, which since
     * E1 it does for anything a task emits.
     */
    record Chunk(String blockId, NaruLogMode mode, String text, boolean finalFragment, long taskId)
            implements TranscriptEvent {

        public Chunk(String blockId, NaruLogMode mode, String text, boolean finalFragment) {
            this(blockId, mode, text, finalFragment, NaruOutput.NO_TASK);
        }
    }

    /** The block is complete and may be collapsed/frozen. */
    record Closed(String blockId) implements TranscriptEvent {
    }

    /**
     * One model tool call changed state: started, parked on a question, settled, or failed
     * (E1). A card is keyed by {@code (taskId, callId)} across the batch — {@code STARTED} and
     * the matching final event are separate events, and {@code callId} may be null when the model
     * did not supply one, which is why the key falls back to the tool name.
     * <p>
     * This is never dropped under load and never merged: a call that settled without a card would
     * leave the tools panel listing a call that looks like it is still running.
     */
    record ToolCall(NaruToolCallEvent call) implements TranscriptEvent {
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
