package net.thevpc.naru.desktop.host;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.model.NaruMessage;

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

    /**
     * A new renderable block begins. Everything after it belongs to {@code blockId} until {@link Closed}.
     * <p>
     * {@code directiveResult} marks output the host produced while running a {@code /}-directive
     * for the user. It is conversation content — a reply to something typed — so the conversation
     * view shows it rather than treating it as engine noise.
     */
    record Opened(String blockId, NaruLogMode mode, long taskId, boolean directiveResult) implements TranscriptEvent {

        /** Output that the engine did not attribute to a task. */
        public Opened(String blockId, NaruLogMode mode) {
            this(blockId, mode, NaruOutput.NO_TASK, false);
        }
    }

    /**
     * Text appended to an open block. {@code finalFragment} marks the last fragment of a
     * message, which is when a renderer may do the expensive one-shot work (markdown parse,
     * syntax highlight) instead of re-doing it per token. It does not close the block: a run of
     * whole messages of one mode/task shares one block until {@link Closed} arrives.
     * <p>
     * {@code taskId} is {@link NaruOutput#NO_TASK} unless the engine stamped the line, which since
     * E1 it does for anything a task emits. {@code directiveResult} is set on every fragment of a
     * block opened for a directive, so a fragment that arrives first can carry the mark too.
     * <p>
     * {@code error} is the engine's own severity ({@code NMsg.isError()}). It travels as a fact
     * rather than being inferred from the text because it changes how the block is kept: narration
     * logged in the same mode as a failure is transient, a failure is not.
     */
    record Chunk(String blockId, NaruLogMode mode, String text, boolean finalFragment, long taskId,
                 boolean directiveResult, boolean error) implements TranscriptEvent {

        public Chunk(String blockId, NaruLogMode mode, String text, boolean finalFragment, long taskId,
                     boolean directiveResult) {
            this(blockId, mode, text, finalFragment, taskId, directiveResult, false);
        }

        public Chunk(String blockId, NaruLogMode mode, String text, boolean finalFragment, long taskId) {
            this(blockId, mode, text, finalFragment, taskId, false, false);
        }

        public Chunk(String blockId, NaruLogMode mode, String text, boolean finalFragment) {
            this(blockId, mode, text, finalFragment, NaruOutput.NO_TASK, false, false);
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

    /** A durable, structured conversation item; hosts may render its typed parts as components. */
    record Message(String blockId, long taskId, long sequence, NaruMessage message) implements TranscriptEvent {
        public Message {
            message = message == null ? null : message.copy();
        }

        public Message(String blockId, long taskId, NaruMessage message) {
            this(blockId, taskId, Long.MAX_VALUE, message);
        }

        @Override public NaruMessage message() { return message == null ? null : message.copy(); }
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
