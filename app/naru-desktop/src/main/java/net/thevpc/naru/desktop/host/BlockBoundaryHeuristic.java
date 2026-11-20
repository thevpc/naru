package net.thevpc.naru.desktop.host;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;

/**
 * Decides where blocks begin and end.
 * <p>
 * This is a heuristic, and it is deliberately in one class so it can be deleted the moment the
 * engine marks boundaries itself. It no longer has to guess the owner of a line: since E1 the
 * engine stamps {@code taskId} on output, so a task switch is a fact rather than an inference
 * (plan §0 check 2). What is still a guess is where a *turn* of one task ends.
 * <p>
 * Rules, in order:
 * <ul>
 *   <li>a different mode closes the open block and starts a new one — that is what separates
 *       thinking from answer, which the terminal draws as two gutter colours;</li>
 *   <li>a different task does the same, because two tasks interleaving the same mode must not
 *       be merged into one row that claims a single owner;</li>
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
    private long currentTaskId = NaruOutput.NO_TASK;
    private boolean currentOpen;
    private boolean currentWasStreamed;
    private long sequence;

    /** The block a streamed fragment is about to be appended to, opening one if needed. */
    public String openForStream(NaruLogMode mode, long taskId) {
        if (currentOpen && currentMode == mode && currentTaskId == taskId) {
            currentWasStreamed = true;
            return currentId;
        }
        close();
        currentId = "b" + (++sequence);
        currentMode = mode;
        currentTaskId = taskId;
        currentOpen = true;
        currentWasStreamed = true;
        return currentId;
    }

    public String openForStream(NaruLogMode mode) {
        return openForStream(mode, NaruOutput.NO_TASK);
    }

    /** True when a whole message has to open a fresh block rather than join the current one. */
    public boolean startsNewBlock(NaruLogMode mode, long taskId) {
        return !currentOpen || currentMode != mode || currentTaskId != taskId || currentWasStreamed;
    }

    public boolean startsNewBlock(NaruLogMode mode) {
        return startsNewBlock(mode, NaruOutput.NO_TASK);
    }

    public String openForWhole(NaruLogMode mode, long taskId) {
        if (startsNewBlock(mode, taskId)) {
            close();
            currentId = "b" + (++sequence);
            currentMode = mode;
            currentTaskId = taskId;
            currentOpen = true;
        }
        currentWasStreamed = false;
        return currentId;
    }

    public String openForWhole(NaruLogMode mode) {
        return openForWhole(mode, NaruOutput.NO_TASK);
    }

    /** The id currently open, or null. */
    public String currentBlockId() {
        return currentOpen ? currentId : null;
    }

    public NaruLogMode currentBlockMode() {
        return currentOpen ? currentMode : null;
    }

    public long currentBlockTaskId() {
        return currentOpen ? currentTaskId : NaruOutput.NO_TASK;
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
