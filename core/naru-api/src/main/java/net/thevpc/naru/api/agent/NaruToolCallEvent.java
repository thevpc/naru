package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruToolCall;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A typed report that one model tool call changed state: it started, parked on a question,
 * returned, or failed.
 *
 * <p>Before this existed, a tool call could only be seen as the {@code PROGRESS} line the
 * statement prints — an abbreviation of the result, decorated for a terminal, with no stable
 * key. A host that wants to show the tool name, its arguments, how long it took and the
 * <em>whole</em> result needs a structured event, which is what this is. The log line stays
 * exactly as it was: this is added beside it, not instead of it.
 *
 * <p>Every field carries what the engine actually knows. In particular there is no exit code:
 * a {@code NaruTool} returns a string, and codes such as a shell's belong inside that string,
 * so a host must not promise one.
 *
 * <h2>Threading</h2>
 * Fired on whichever worker thread ran the statement, like {@link NaruInteraction#write}, and
 * several tasks can fire at once. The consumer must not block. A consumer that throws is
 * ignored — this is a side channel and must never fail the tool call it describes.
 */
public class NaruToolCallEvent {

    /**
     * The four states a host can distinguish. A host that only knows how to draw
     * {@link #RESULT} and {@link #FAILED} still works: it ignores the others and shows a
     * card when the call settles.
     */
    public enum Kind {
        /**
         * The call is about to be dispatched. Absent for a call the engine rejected before it
         * got that far (a blank tool name), so a host must be able to draw a final event it
         * never saw started.
         */
        STARTED,
        /**
         * An interactive tool returned its waiting marker, so the call now blocks until the
         * user answers. A later {@link #RESULT} or {@link #FAILED} closes the same
         * {@link #callId()}.
         */
        PARKED,
        /** The tool returned normally. {@link #result()} holds the full, untruncated text. */
        RESULT,
        /**
         * The call ended in the engine's error shape: unknown tool, a permission denial, or a
         * tool whose own contract reports failure by returning {@code ERROR: …}. The text is
         * still the full result, so a denial can be shown as a complete card.
         */
        FAILED,
    }

    /**
     * No time was measured for this event — either because the call has not run yet
     * ({@link Kind#STARTED}, {@link Kind#PARKED}) or because the leg that finished it started
     * in an earlier activation and its clock did not survive the save.
     */
    public static final long UNKNOWN_DURATION = -1;

    private final Kind kind;
    private final long taskId;
    private final String callId;
    private final String toolName;
    private final Map<String, Object> arguments;
    private final String result;
    private final long durationMillis;

    private NaruToolCallEvent(Kind kind, long taskId, NaruToolCall call, String result,
                              long durationMillis) {
        this.kind = kind;
        this.taskId = taskId;
        // the id comes from the model and some models omit it: a host keys a card on this and
        // must be able to tell "no id" from "the empty id", so it is kept as it arrived
        this.callId = call == null ? null : call.getId();
        this.toolName = call == null ? null : call.getName();
        Map<String, Object> a = call == null ? null : call.getArguments();
        // a copy, and in insertion order: the tool may still be running and mutating its
        // argument map while a host is drawing this event
        this.arguments = a == null || a.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(a));
        this.result = result;
        this.durationMillis = durationMillis;
    }

    /** The call is about to run. */
    public static NaruToolCallEvent started(long taskId, NaruToolCall call) {
        return new NaruToolCallEvent(Kind.STARTED, taskId, call, null, UNKNOWN_DURATION);
    }

    /** The call is waiting for a user answer. */
    public static NaruToolCallEvent parked(long taskId, NaruToolCall call) {
        return new NaruToolCallEvent(Kind.PARKED, taskId, call, null, UNKNOWN_DURATION);
    }

    /**
     * The call returned.
     *
     * @param result         the full text the tool produced, never abbreviated
     * @param durationMillis elapsed dispatch time, or {@link #UNKNOWN_DURATION}
     */
    public static NaruToolCallEvent result(long taskId, NaruToolCall call, String result,
                                           long durationMillis) {
        return new NaruToolCallEvent(Kind.RESULT, taskId, call, result, durationMillis);
    }

    /**
     * The call ended in the engine's error shape.
     *
     * @param result         the full error text, never abbreviated
     * @param durationMillis elapsed dispatch time, or {@link #UNKNOWN_DURATION}
     */
    public static NaruToolCallEvent failed(long taskId, NaruToolCall call, String result,
                                           long durationMillis) {
        return new NaruToolCallEvent(Kind.FAILED, taskId, call, result, durationMillis);
    }

    public Kind kind() {
        return kind;
    }

    /** The task that ran the call. Never guessed by the host any more (plan §0 check 2). */
    public long taskId() {
        return taskId;
    }

    /** The model's id for this call; may be null or blank when the model did not supply one. */
    public String callId() {
        return callId;
    }

    public String toolName() {
        return toolName;
    }

    /** Unmodifiable copy of the arguments, in the order the model sent them. Never null. */
    public Map<String, Object> arguments() {
        return arguments;
    }

    /**
     * The full result text for {@link Kind#RESULT} and {@link Kind#FAILED}; null for
     * {@link Kind#STARTED} and {@link Kind#PARKED}, where there is nothing to report yet.
     */
    public String result() {
        return result;
    }

    /** Dispatch time in milliseconds, or {@link #UNKNOWN_DURATION}. */
    public long durationMillis() {
        return durationMillis;
    }

    /** Whether this event settles the call, which is when a card stops showing "running…". */
    public boolean isFinal() {
        return kind == Kind.RESULT || kind == Kind.FAILED;
    }

    @Override
    public String toString() {
        return "NaruToolCallEvent[" + kind + " task=" + taskId + " call=" + callId
                + " tool=" + toolName + " args=" + arguments.size()
                + " durationMs=" + durationMillis
                + (result == null ? "" : " resultLength=" + result.length()) + "]";
    }
}
