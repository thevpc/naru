package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.model.NaruMessage;

import java.time.Duration;
import java.time.Instant;

/**
 * One renderable run of output — the unit the transcript scrolls, collapses and re-renders.
 * <p>
 * A block is mutable only while it is open. Once {@link #closed} is set its text never changes
 * again, which is what makes the expensive work legal: markdown is parsed for a closed block,
 * on a worker thread, and cached here ({@link #html()}, {@link #htmlLength()}) so a row is
 * never re-parsed while it scrolls past. A live block is always drawn as plain text — Phase 0
 * measured an HTML cell per row at 14.8 s for a 50 000-row load against 26 ms for plain text
 * (plan §4.3).
 * <p>
 * EDT-confined like the model that owns it; nothing here synchronises.
 */
public final class TranscriptBlock {

    private final String id;
    private final NaruLogMode mode;
    private final int index;
    /**
     * The task that emitted this block, or {@link NaruOutput#NO_TASK}. Set at creation from the
     * engine's own stamp since E1, which is what lets a row say "#12 wrote this" instead of the
     * host guessing from ordering.
     */
    private final long taskId;
    /** Non-null for a tool card: the event that opened it, kept so a widget can show details. */
    private NaruToolCallEvent toolCall;
    private NaruMessage message;
    private long messageSequence = Long.MAX_VALUE;
    /**
     * Output the host produced while running a {@code /}-directive for the user. It is conversation
     * content — a reply to something typed — so the conversation view shows it even though it
     * carries the same {@link NaruLogMode#AGENT_RESPONSE} the engine also uses for a plain error.
     */
    private boolean directiveResult;
    /**
     * Live narration that the next real message supersedes: "Model: gpt-4o…", "Tool: file_read",
     * "⏹ Interrupted.". A transient block is shown while the turn runs and dropped from the
     * conversation once a durable message lands; the Logs tab keeps it. An error is never
     * transient, however it is logged, because a failure the user never saw is a failure they
     * cannot act on.
     */
    private boolean transientBlock;

    private final StringBuilder text = new StringBuilder();
    private boolean closed;
    private boolean collapsed;
    private boolean expanded;
    private Instant startedAt;
    private Instant endedAt;

    /** Cached markdown for the closed text; null until rendered, -1 when never needed. */
    private String html;
    private int htmlLength = -1;

    public TranscriptBlock(String id, NaruLogMode mode, int index) {
        this(id, mode, NaruOutput.NO_TASK, index);
    }

    public TranscriptBlock(String id, NaruLogMode mode, long taskId, int index) {
        this.id = id;
        this.mode = mode;
        this.taskId = taskId;
        this.index = index;
        this.startedAt = Instant.now();
        this.collapsed = BlockStyle.of(mode).collapsedByDefault();
    }

    public String id() {
        return id;
    }

    public NaruLogMode mode() {
        return mode;
    }

    public long taskId() {
        return taskId;
    }

    /** The event that made this block a tool card, or null for an ordinary output block. */
    public NaruToolCallEvent toolCall() {
        return toolCall;
    }

    void setToolCall(NaruToolCallEvent event) {
        this.toolCall = event;
    }

    /** True once a final event settled the card, so nothing more can arrive for it. */
    public boolean isToolCard() {
        return toolCall != null;
    }

    public NaruMessage message() { return message == null ? null : message.copy(); }

    public void setMessage(NaruMessage message) { setMessage(message, Long.MAX_VALUE); }

    public void setMessage(NaruMessage message, long sequence) {
        this.message = message == null ? null : message.copy();
        this.messageSequence = sequence;
    }

    public long messageSequence() { return messageSequence; }

    public boolean isChatMessage() { return message != null; }

    /** True when this block is the result of a {@code /}-directive the user ran. */
    public boolean isDirectiveResult() { return directiveResult; }

    void setDirectiveResult(boolean value) { this.directiveResult = value; }

    /** True for live narration the next real message supersedes. */
    public boolean isTransient() { return transientBlock; }

    void setTransient(boolean value) { this.transientBlock = value; }

    /** Position in the model at creation time; only used for stable sort/ordering hints. */
    public int index() {
        return index;
    }

    public void append(String s) {
        if (closed || s == null || s.isEmpty()) {
            return;
        }
        text.append(s);
    }

    public void close() {
        if (!closed) {
            closed = true;
            endedAt = Instant.now();
        }
    }

    public String text() {
        return text.toString();
    }

    public int length() {
        return text.length();
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean value) {
        this.collapsed = value;
    }

    /**
     * True once the user unfolded this block's body. A body longer than the fold is always shown
     * as its head — measuring every line of it is what makes a layout pass take seconds — so the
     * only way to see the rest is to ask, and that answer has to outlive the card it was given to.
     */
    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean value) {
        this.expanded = value;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    /** Wall-clock the block took, for the collapsed summary line ("Thought for 12 s"). */
    public String elapsedLabel() {
        Instant end = endedAt;
        if (end == null) {
            return null;
        }
        long seconds = Duration.between(startedAt, end).toSeconds();
        if (seconds < 1) {
            return "less than a second";
        }
        if (seconds < 60) {
            return seconds + " s";
        }
        return (seconds / 60) + " min " + (seconds % 60) + " s";
    }

    public String html() {
        return html;
    }

    public void setHtml(String value, int forLength) {
        this.html = value;
        this.htmlLength = forLength;
    }

    /** True when the cached html covers the whole current text, so a re-render is wasted work. */
    public boolean htmlIsCurrent() {
        return closed && html != null && htmlLength == text.length();
    }
}
