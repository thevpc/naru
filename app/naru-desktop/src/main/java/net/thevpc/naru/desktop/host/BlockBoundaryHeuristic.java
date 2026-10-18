package net.thevpc.naru.desktop.host;

import net.thevpc.naru.api.agent.NaruLogMode;

/**
 * Decides where blocks begin and end.
 * <p>
 * This is a heuristic, and it is deliberately in one class so it can be deleted the moment the
 * engine marks boundaries itself (plan §9, and E1 should stamp {@code taskId} onto output —
 * {@code NaruOutput} carries no block, task or turn identifier today).
 * <p>
 * Rules, in order:
 * <ul>
 *   <li>a different mode closes the open block and starts a new one — that is what separates
 *       thinking from answer, which the terminal draws as two gutter colours;</li>
 *   <li>a whole {@code write} message closes any open streamed block first, because the
 *       {@code NaruInteraction} contract says an unrelated log line must never land in the
 *       middle of a half-drawn sentence;</li>
 *   <li>two consecutive whole messages of the same mode stay in one block, which keeps the
 *       per-mode log from exploding into thousands of one-line blocks.</li>
 * </ul>
 * Not thread-safe by design: the host owns exactly one instance and drives it from whichever
 * worker thread publishes, under the host's publish lock.
 */
public final class BlockBoundaryHeuristic {

    private String currentId;
    private NaruLogMode currentMode;
    private boolean currentOpen;
    private boolean currentWasStreamed;
    private long sequence;

    /** The block a streamed fragment is about to be appended to, opening one if needed. */
    public String openForStream(NaruLogMode mode) {
        if (currentOpen && currentMode == mode) {
            currentWasStreamed = true;
            return currentId;
        }
        close();
        currentId = "b" + (++sequence);
        currentMode = mode;
        currentOpen = true;
        currentWasStreamed = true;
        return currentId;
    }

    /** True when a whole message has to open a fresh block rather than join the current one. */
    public boolean startsNewBlock(NaruLogMode mode) {
        return !currentOpen || currentMode != mode || currentWasStreamed;
    }

    public String openForWhole(NaruLogMode mode) {
        if (startsNewBlock(mode)) {
            close();
            currentId = "b" + (++sequence);
            currentMode = mode;
            currentOpen = true;
        }
        currentWasStreamed = false;
        return currentId;
    }

    /** The id currently open, or null. */
    public String currentBlockId() {
        return currentOpen ? currentId : null;
    }

    public NaruLogMode currentBlockMode() {
        return currentOpen ? currentMode : null;
    }

    /** Emits nothing itself; the caller turns the return value into a {@link TranscriptEvent.Closed}. */
    public String close() {
        if (!currentOpen) {
            return null;
        }
        String id = currentId;
        currentOpen = false;
        currentMode = null;
        return id;
    }
}
