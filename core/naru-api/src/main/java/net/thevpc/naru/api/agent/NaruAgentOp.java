package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruToolCall;

public class NaruAgentOp {
    public Type type;
    public NaruToolCall call;
    public enum Type {
        READLINE,
        CALL_MODEL,
        TOOL_CALL,
        EXECUTE_SCRIPT_LINE
    }

    public static NaruAgentOp ofReadLine() {
        return new NaruAgentOp(Type.READLINE);
    }
    public static NaruAgentOp ofToolCall(NaruToolCall call) {
        return new NaruAgentOp(call);
    }
    public static NaruAgentOp ofCallModel() {
        return new NaruAgentOp(Type.CALL_MODEL);
    }

    public NaruAgentOp(Type type) {
        this.type = type;
    }
    public NaruAgentOp(NaruToolCall call) {
        this.type = Type.TOOL_CALL;
        this.call = call;
    }
}
