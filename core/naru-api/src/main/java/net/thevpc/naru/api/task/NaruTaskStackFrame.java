package net.thevpc.naru.api.task;

import java.util.Map;

public class NaruTaskStackFrame {
    private String name;
    private int index;
    private String instruction;
    private Map<String, Object> params;
    private Map<String, Object> localVars;

    public NaruTaskStackFrame(String name, int index, String instruction,Map<String, Object> params,Map<String, Object> localVars) {
        this.name = name;
        this.index = index;
        this.instruction = instruction;
        this.params = params;
        this.localVars = localVars;
    }

    public Map<String, Object> params() {
        return params;
    }

    public Map<String, Object> localVars() {
        return localVars;
    }

    public String name() {
        return name;
    }

    public int index() {
        return index;
    }

    public String instruction() {
        return instruction;
    }
}
