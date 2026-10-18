package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The tool-call list behind the Tools tab: one row per call, updated as its lifecycle events
 * arrive (E1). EDT-confined and Swing-free, like {@link TranscriptModel}, so it is testable
 * headless and a future non-Swing front end can use the same thing.
 * <p>
 * This is deliberately a second view over the same events rather than a read-back of the
 * transcript. The transcript is a stream that gets trimmed to its character budget; a list of
 * "what did the agent run, for how long, and what came back" has to stay put while the user
 * scrolls through it, and a row must not disappear because its output was long.
 * <p>
 * It is bounded, though, and bounded in the honest direction: a row either holds the whole result
 * or it is dropped and counted in {@link #droppedRows()}. Silently keeping the first 300 characters
 * is exactly the behaviour this phase removes.
 */
public final class ToolCallModel {

    /** Rows kept before the oldest is dropped. A session that calls tools 500 times is rare. */
    public static final int MAX_ROWS = 500;
    /** Total result text retained across the backlog — about 4 MB, the transcript's own budget. */
    public static final int MAX_RETAINED_RESULT_CHARS = 4 * 1024 * 1024;

    public enum Status {
        /** Dispatched, no answer back yet. */
        RUNNING,
        /** An interactive tool is parked until the user answers. */
        WAITING,
        /** Returned. */
        OK,
        /** Ended in the engine's error shape: unknown tool, denial, or a failing tool. */
        FAILED,
    }

    /** Everything the panel and its detail pane need about one call. */
    public record Row(long taskId, String callId, String toolName, Map<String, Object> arguments,
                      Status status, String result, long durationMillis, Instant startedAt,
                      Instant settledAt) {

        /** Wall clock the call took, when the panel measured it itself. */
        public String measuredDuration() {
            if (durationMillis >= 0) {
                return net.thevpc.naru.desktop.util.ToolCardText.duration(durationMillis);
            }
            if (settledAt == null || startedAt == null) {
                return null;
            }
            return net.thevpc.naru.desktop.util.ToolCardText
                    .duration(java.time.Duration.between(startedAt, settledAt).toMillis());
        }

        public int resultChars() {
            return result == null ? 0 : result.length();
        }
    }

    public interface Listener {
        void toolCallsChanged();
    }

    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Deque<Integer>> openByCall = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private long resultChars;
    private int droppedRows;

    /** Apply one coalesced host batch. Called on the EDT. */
    public void apply(List<TranscriptEvent> batch) {
        EDT.check("ToolCallModel.apply");
        boolean changed = false;
        for (TranscriptEvent e : batch) {
            if (e instanceof TranscriptEvent.ToolCall tc) {
                changed |= update(tc.call());
            } else if (e instanceof TranscriptEvent.HostClosed hc) {
                // the activation is over, so a row still marked RUNNING never settled. Say so
                // rather than leave the list lying about a call that will never come back
                changed |= settleOpen(hc.reason());
            }
        }
        changed |= trim();
        if (changed) {
            fire();
        }
    }

    private boolean update(NaruToolCallEvent event) {
        if (event == null) {
            return false;
        }
        String key = key(event);
        if (event.kind() == NaruToolCallEvent.Kind.STARTED
                || event.kind() == NaruToolCallEvent.Kind.PARKED) {
            Integer at = null;
            if (event.kind() == NaruToolCallEvent.Kind.PARKED) {
                // waiting annotates the open row; STARTED always begins a new call, because a model
                // that sends no call id can start a second one before the first settles
                Deque<Integer> q = openByCall.get(key);
                Integer first = q == null ? null : q.peekFirst();
                if (first != null && first < rows.size()) {
                    at = first;
                }
            }
            Status st = event.kind() == NaruToolCallEvent.Kind.PARKED ? Status.WAITING : Status.RUNNING;
            if (at != null) {
                Row old = rows.get(at);
                rows.set(at, new Row(old.taskId(), old.callId(), old.toolName(), old.arguments(), st,
                        null, NaruToolCallEvent.UNKNOWN_DURATION, old.startedAt(), null));
                return true;
            }
            rows.add(new Row(event.taskId(), event.callId(), event.toolName(), event.arguments(),
                    st, null, NaruToolCallEvent.UNKNOWN_DURATION, Instant.now(), null));
            openByCall.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(rows.size() - 1);
            return true;
        }
        Deque<Integer> q = openByCall.get(key);
        Integer at = q == null ? null : q.pollFirst();
        if (q != null && q.isEmpty()) {
            openByCall.remove(key);
        }
        Status st = event.kind() == NaruToolCallEvent.Kind.FAILED ? Status.FAILED : Status.OK;
        String result = event.result();
        if (at != null && at < rows.size() && rows.get(at).status() != Status.OK
                && rows.get(at).status() != Status.FAILED) {
            Row old = rows.get(at);
            rows.set(at, new Row(old.taskId(), old.callId(), old.toolName(), old.arguments(), st,
                    result, event.durationMillis(), old.startedAt(), Instant.now()));
            resultChars += length(result);
            return true;
        }
        // no open row for it: the call was rejected before dispatch, or its STARTED was in an
        // activation whose rows have since been dropped
        rows.add(new Row(event.taskId(), event.callId(), event.toolName(), event.arguments(), st,
                result, event.durationMillis(), null, Instant.now()));
        resultChars += length(result);
        return true;
    }

    /**
     * Drop whole rows from the front until both caps fit. Index-keyed open rows make this the one
     * place where indices move, so it re-keys them in the same pass — a parked call whose row
     * survived must still settle into itself.
     * <p>
     * The newest row is never evicted. A single 5 MB tool result is over the retained-history cap,
     * and dropping the call that just finished to make room for history would hide exactly the line
     * the user is looking at; so the cap bounds the <em>backlog</em> and one oversized current call
     * is allowed to exceed it by its own size.
     */
    private boolean trim() {
        int first = 0;
        long retained = resultChars;
        while (first < rows.size() - 1
                && (rows.size() - first > MAX_ROWS || retained > MAX_RETAINED_RESULT_CHARS)) {
            retained -= length(rows.get(first).result());
            first++;
        }
        if (first == 0) {
            return false;
        }
        resultChars = retained;
        droppedRows += first;
        rows.subList(0, first).clear();
        Map<String, Deque<Integer>> rekeyed = new HashMap<>();
        for (Map.Entry<String, Deque<Integer>> e : openByCall.entrySet()) {
            Deque<Integer> shifted = new ArrayDeque<>();
            for (int i : e.getValue()) {
                if (i >= first) {
                    shifted.addLast(i - first);
                }
                // an open row that got dropped is gone; its card stays in the transcript
            }
            if (!shifted.isEmpty()) {
                rekeyed.put(e.getKey(), shifted);
            }
        }
        openByCall.clear();
        openByCall.putAll(rekeyed);
        return true;
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    /** Mark every unsettled row FAILED, because this activation cannot deliver its result. */
    private boolean settleOpen(String reason) {
        if (openByCall.isEmpty()) {
            return false;
        }
        boolean changed = false;
        String text = "no result: " + (reason == null || reason.isBlank() ? "the host closed" : reason);
        for (Deque<Integer> q : openByCall.values()) {
            for (int i : q) {
                if (i < rows.size()) {
                    Row old = rows.get(i);
                    if (old.status() == Status.RUNNING || old.status() == Status.WAITING) {
                        rows.set(i, new Row(old.taskId(), old.callId(), old.toolName(), old.arguments(),
                                Status.FAILED, text, NaruToolCallEvent.UNKNOWN_DURATION,
                                old.startedAt(), Instant.now()));
                        resultChars += length(text);
                        changed = true;
                    }
                }
            }
        }
        openByCall.clear();
        return changed;
    }

    private static String key(NaruToolCallEvent event) {
        String id = event.callId();
        return event.taskId() + "\u0000" + (id == null || id.isBlank() ? String.valueOf(event.toolName()) : id);
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }

    public int size() {
        return rows.size();
    }

    /** Rows discarded by the caps, so the panel can say the list is not the whole history. */
    public int droppedRows() {
        return droppedRows;
    }

    public Row rowAt(int index) {
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.toolCallsChanged();
        }
    }

    /** Forget everything; used when a session reloads or the window switches project. */
    public void clear() {
        EDT.check("ToolCallModel.clear");
        rows.clear();
        openByCall.clear();
        resultChars = 0;
        droppedRows = 0;
        fire();
    }
}
