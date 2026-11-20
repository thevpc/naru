package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.model.NaruToolCall;

/** A model tool whose execution pauses for a user answer before it can return a result. */
public interface NaruInteractiveTool extends NaruTool {
    /** Marker returned by execute while waiting for the user. */
    String WAITING_FOR_INPUT = "__NARU_WAITING_FOR_INPUT__";

    /** Complete the suspended call with the user's answer. */
    String complete(NaruToolCall call, String answer);
}
