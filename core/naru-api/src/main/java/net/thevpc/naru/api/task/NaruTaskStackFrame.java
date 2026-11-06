package net.thevpc.naru.api.task;

import net.thevpc.nuts.elem.NElement;

import java.util.Map;

public class NaruTaskStackFrame {
    private String name;
    private int index;
    private String instruction;
    private Map<String, NElement> params;
    private Map<String, NElement> localVars;

    public NaruTaskStackFrame(String name, int index, String instruction,Map<String, NElement> params,Map<String, NElement> localVars) {
        this.name = name;
        this.index = index;
        this.instruction = instruction;
        this.params = params;
        this.localVars = localVars;
    }

    public Map<String, NElement> params() {
        return params;
    }

    public Map<String, NElement> localVars() {
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
