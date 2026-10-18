package net.thevpc.naru.api.agent;

/** Ordered activation event. Payload is the event-specific value described by kind. */
public record NaruTurnEvent(long seq, Kind kind, Object payload) {
    public enum Kind {
        CHUNK,
        LOG,
        MESSAGE_COMPLETE,
        INPUT_REQUESTED,
        /** Payload is the {@link NaruToolCallEvent} that settled or opened the call. */
        TOOL_CALL,
        HIBERNATING,
        HIBERNATED,
        ERROR
    }
}
