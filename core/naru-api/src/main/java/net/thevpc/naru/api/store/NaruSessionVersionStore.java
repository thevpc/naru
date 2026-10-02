package net.thevpc.naru.api.store;

import net.thevpc.nuts.util.NOptional;

import java.util.List;

/**
 * Versioned, self-contained session state.
 *
 * <p>Separate from {@link NaruSessionStore} because the two have opposite invariants. The
 * live store is rewritten constantly and is authoritative for exactly one moment; a
 * version is written once, never touched again, and is authoritative forever. Blending them
 * would mean every fast path through the hot store had to be correct about historical
 * consistency, and every historical read would have to be fast about a file that is
 * rewritten sixty times a turn.
 *
 * <p>Commits are supplied their state by the caller through a
 * {@link NaruRestoreResult.Snapshot}. The store never reaches back into the engine to ask
 * "what does the session look like now": the engine is the only thing that knows, and the
 * moment it hands the state over is the moment it is consistent.
 */
public interface NaruSessionVersionStore {

    /** The version HEAD points at, or empty for a session that has never been committed. */
    NOptional<NaruVersionRef> head();

    /** All versions, newest first. */
    List<NaruVersionRef> list();

    /** Looks a version up by sequence number or by label. */
    NOptional<NaruVersionRef> find(String seqOrLabel);

    /**
     * Commits the state the snapshot yields.
     *
     * <p>If the state hashes to a version this store already has, that version is returned
     * with its sequence number unchanged and nothing is written. Committing the same state
     * twice must not be a way to fill the disk, and the gapless counter would otherwise
     * grow a hole that {@code restore <n>} has to skip.
     */
    NaruVersionRef commit(String reason, String label, NaruRestoreResult.Snapshot snapshot);

    /**
     * Gives a version a name. Refuses to steal an existing label rather than silently
     * moving it: a label that means two things is worse than a duplicate label rejected.
     */
    NaruVersionRef label(String seqOrLabel, String label);

    /**
     * Reads a version's state. The state is delivered to a {@link NaruRestoreResult.Sink}
     * rather than returned, so a caller can apply it inside its own critical section rather
     * than holding a whole version's worth of objects in memory between the read and the
     * write.
     */
    boolean restore(String seqOrLabel, NaruRestoreResult.Sink sink);

    NaruRestoreResult.Diff diff(String from, String to);

    /**
     * Applies a policy, removing the versions it does not protect and then the objects
     * nothing refers to any more.
     */
    NaruGcResult gc(NaruVersionPolicy policy);

    /** What a sweep removed. */
    class NaruGcResult {
        private final int versionsRemoved;
        private final int versionsKept;
        private final long bytesReclaimed;

        public NaruGcResult(int versionsRemoved, int versionsKept, long bytesReclaimed) {
            this.versionsRemoved = versionsRemoved;
            this.versionsKept = versionsKept;
            this.bytesReclaimed = bytesReclaimed;
        }

        public int versionsRemoved() {
            return versionsRemoved;
        }

        public int versionsKept() {
            return versionsKept;
        }

        public long bytesReclaimed() {
            return bytesReclaimed;
        }

        @Override
        public String toString() {
            return "NaruGcResult{removed=" + versionsRemoved + ", kept=" + versionsKept
                    + ", bytes=" + bytesReclaimed + '}';
        }
    }
}