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

    private final long sequence;
    private final Instant instant;
    private final NaruLogMode mode;
    private final NMsg message;

    public NaruOutput(long sequence, Instant instant, NaruLogMode mode, NMsg message) {
        this.sequence = sequence;
        this.instant = Objects.requireNonNull(instant, "instant");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.message = Objects.requireNonNull(message, "message");
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

    @Override
    public String toString() {
        return "NaruOutput[" + sequence + " " + mode + " " + message + "]";
    }
}
