package net.thevpc.naru.api.store;

import net.thevpc.nuts.concurrent.NCallable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of restoring a version into a live session.
 *
 * <p>Reported as counts rather than as a boolean, because a partial restore is a real
 * outcome worth distinguishing from a failed one. A version committed while a tool was
 * rewriting a file, then restored into a session that has since been told to reload, is
 * not going to restore cleanly; saying "restored 3 of 4 tasks" lets the caller say
 * something true about it.
 */
public class NaruRestoreResult {

    private final String version;
    private final long tasksRestored;
    private final long tasksTotal;
    private final long messagesRestored;
    private final List<String> problems = new ArrayList<>();

    public NaruRestoreResult(String version, long tasksRestored, long tasksTotal,
                             long messagesRestored, List<String> problems) {
        this.version = version;
        this.tasksRestored = tasksRestored;
        this.tasksTotal = tasksTotal;
        this.messagesRestored = messagesRestored;
        if (problems != null) {
            this.problems.addAll(problems);
        }
    }

    public String version() {
        return version;
    }

    public long tasksRestored() {
        return tasksRestored;
    }

    public long tasksTotal() {
        return tasksTotal;
    }

    public long messagesRestored() {
        return messagesRestored;
    }

    public List<String> problems() {
        return problems;
    }

    public boolean isComplete() {
        return problems.isEmpty() && tasksRestored == tasksTotal;
    }

    @Override
    public String toString() {
        return "NaruRestoreResult{version=" + version + ", tasks=" + tasksRestored + "/" + tasksTotal
                + ", messages=" + messagesRestored + (problems.isEmpty() ? "" : ", problems=" + problems) + '}';
    }

    /**
     * A diff between two versions, at the granularity a person can act on.
     *
     * <p>Task-level, not message-level: the interesting question after restoring is
     * "which tasks are different", and a message-level diff of a hundred-turn
     * conversation is unreadable and slow to compute for no gain.
     */
    public static class Diff {
        private final String from;
        private final String to;
        private final List<String> added = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();
        private final Map<String, String> changed = new LinkedHashMap<>();

        public Diff(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public String from() {
            return from;
        }

        public String to() {
            return to;
        }

        public List<String> added() {
            return added;
        }

        public List<String> removed() {
            return removed;
        }

        /** task id to a one-line summary of what differs. */
        public Map<String, String> changed() {
            return changed;
        }

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }

        @Override
        public String toString() {
            return "Diff{" + from + " -> " + to + ": +" + added.size() + " -" + removed.size()
                    + " ~" + changed.size() + '}';
        }
    }

    /** Hands a version's state to whoever is restoring it. */
    public interface Sink {
        void accept(NaruSessionState state);
    }

    /** Produces the current state, at a moment when it is stable enough to read. */
    public interface Snapshot extends NCallable<NaruSessionState> {
    }
}