package net.thevpc.naru.api.agent;

import net.thevpc.nuts.text.NMsg;

import java.time.Instant;
import java.util.Objects;

/**
 * One piece of session output, as produced by {@link NaruInteraction#write}.
 * <p>
 * Carries a monotonic per-session {@link #sequence()} so a consumer that reconnects can
 * tell what it missed, and an {@link #instant()} so a web front end can stamp arrival time
 * rather than guessing. The {@link #mode()} is preserved so a consumer can filter: a UI
 * showing only {@code AGENT_RESPONSE} should not have to parse styled text to find it.
 *
 * <h2>No styling</h2>
 * {@link #message()} is plain. A terminal implementation renders it, and is the only place
 * that decides on colour and indentation — so a browser or an HTTP client never receives
 * ANSI escapes it would have to strip.
 */
public class NaruOutput {

    /** The output came from the session itself, or from a host that does not stamp. */
    public static final long NO_TASK = 0;

    private final long sequence;
    private final Instant instant;
    private final NaruLogMode mode;
    private final NMsg message;
    private final long taskId;

    public NaruOutput(long sequence, Instant instant, NaruLogMode mode, NMsg message) {
        this(sequence, instant, mode, message, NO_TASK);
    }

    public NaruOutput(long sequence, Instant instant, NaruLogMode mode, NMsg message, long taskId) {
        this.sequence = sequence;
        this.instant = Objects.requireNonNull(instant, "instant");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.message = Objects.requireNonNull(message, "message");
        this.taskId = taskId;
    }

    /** Monotonic within one session, starting at 1. */
    public long sequence() {
        return sequence;
    }

    public Instant instant() {
        return instant;
    }

    public NaruLogMode mode() {
        return mode;
    }

    public NMsg message() {
        return message;
    }

    /**
     * Which task produced this line, or {@link #NO_TASK} when nobody knew. A host with
     * per-task surfaces used to have to guess it from the text; the engine stamps it now.
     */
    public long taskId() {
        return taskId;
    }

    @Override
    public String toString() {
        return "NaruOutput[" + sequence + " " + mode + (taskId == NO_TASK ? "" : " task=" + taskId)
                + " " + message + "]";
    }
}
