package net.thevpc.naru.api.store;

import java.time.Instant;

/**
 * A committed version of a session.
 *
 * <p>{@code seq} is a per-session, gapless counter and {@code hash} is the identity. Both
 * exist because they answer different questions: the counter is what a user types and what
 * they read, and it survives the head moving; the hash is what a store compares to decide
 * whether it already has the objects. A version referenced only by counter cannot be
 * checked for existence in a content-addressed store without reading it first, and a
 * version referenced only by hash is unreadable to a person.
 */
public class NaruVersionRef {

    private final long seq;
    private final String hash;
    private final Instant createdInstant;
    private final String reason;
    private final String label;
    private final boolean head;

    public NaruVersionRef(long seq, String hash, Instant createdInstant, String reason, String label, boolean head) {
        this.seq = seq;
        this.hash = hash;
        this.createdInstant = createdInstant;
        this.reason = reason;
        this.label = label;
        this.head = head;
    }

    public long seq() {
        return seq;
    }

    public String hash() {
        return hash;
    }

    public Instant createdInstant() {
        return createdInstant;
    }

    /** Free text supplied by the caller, e.g. {@code "/session commit before the refactor"}. */
    public String reason() {
        return reason;
    }

    /** A name that survives later commits, or null. */
    public String label() {
        return label;
    }

    public boolean isHead() {
        return head;
    }

    public NaruVersionRef asHead(boolean head) {
        return new NaruVersionRef(seq, hash, createdInstant, reason, label, head);
    }

    public NaruVersionRef withLabel(String label) {
        return new NaruVersionRef(seq, hash, createdInstant, reason, label, head);
    }

    @Override
    public String toString() {
        return "NaruVersionRef{seq=" + seq + ", hash=" + hash
                + (label == null ? "" : ", label=" + label)
                + (head ? ", HEAD" : "") + '}';
    }
}