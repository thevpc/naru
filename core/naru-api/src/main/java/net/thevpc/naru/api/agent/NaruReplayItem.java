package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.nuts.text.NMsg;

/**
 * One renderable item derived from durable history, expressed in the same
 * {@link NaruLogMode} vocabulary a live turn emits.
 *
 * <p>A host draws a session from these rather than from {@link NaruMessage} directly, because a
 * message is a storage unit and not a display unit: one assistant message carries reasoning, an
 * answer and several tool calls, which a transcript shows as several separate items. Deciding
 * that split once, in the engine, is what keeps two hosts from drifting apart — the bug this
 * exists to prevent was a desktop that drew every restored message as the same generic card
 * because nothing told it which of the three it was looking at.
 *
 * <p>Exactly one of {@link #text()} or {@link #toolCall()} describes what to draw.
 * {@link #message()} is the non-null case that matters: an item that carries its stored message is
 * a turn a participant authored, which a host draws as a message card; an item with null was
 * derived — a reasoning segment, a narration line the engine logged — and belongs on the plain
 * channel its mode names. That is the whole distinction a host got wrong before this class existed,
 * by attaching every stored message to every restored block and so labelling engine narration and
 * model reasoning as one more agent turn.
 */
public final class NaruReplayItem {

    private final NaruLogMode mode;
    private final NMsg text;
    private final NaruMessage message;
    private final NaruToolCallEvent toolCall;

    NaruReplayItem(NaruLogMode mode, NMsg text, NaruMessage message, NaruToolCallEvent toolCall) {
        this.mode = mode;
        this.text = text;
        this.message = message;
        this.toolCall = toolCall;
    }

    /**
     * One item a host raises for itself from a message it already holds.
     *
     * <p>{@link NaruTranscriptReplay} produces no item for content that is not part of the
     * conversation — a {@code system} notice, for instance, is context given to the model rather
     * than something a participant said. A host that nevertheless has to show that message (an
     * error it surfaced itself) builds the row here, so the drawing still goes through one item
     * shape instead of a second ad-hoc path.
     */
    public static NaruReplayItem ofMessage(NaruLogMode mode, NaruMessage message) {
        return new NaruReplayItem(mode, message == null ? null : message.getContent(), message, null);
    }

    /** The channel to draw on: the same one the live turn used for this content. */
    public NaruLogMode mode() {
        return mode;
    }

    /** The body, markup tokens included, exactly as the engine logged it. Null for a tool card. */
    public NMsg text() {
        return text;
    }

    /** The stored item this came from, or null when it is a derived part of one. */
    public NaruMessage message() {
        return message;
    }

    /** The settled tool call to draw as a card, or null for a text item. */
    public NaruToolCallEvent toolCall() {
        return toolCall;
    }

    /** True when this item is a tool card rather than a run of text. */
    public boolean isToolCall() {
        return toolCall != null;
    }

    @Override
    public String toString() {
        return "NaruReplayItem[" + mode + (toolCall != null ? " tool=" + toolCall.toolName() : "")
                + (message != null ? " durable" : "") + "]";
    }
}
