package net.thevpc.naru.impl.agent;

import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.routine.RunContext;
import net.thevpc.naru.impl.stmt.NaruStatementHelper;
import net.thevpc.nuts.elem.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class RunContextImpl implements NToElement, RunContext {
    private int pc = -1; // -1 means not currently executing a script
    List<NaruStatement> todo = new ArrayList<>();
    private Integer returnPc = null;          // NEW: where to resume after /return
    private final Map<String, Object> params = new HashMap<>(); // NEW: local param frame
    private final Map<String, Object> state = new HashMap<>(); // Local mutable state
    private String routine; // Local mutable state
    private String runningRoutine; // Local mutable state

    public RunContextImpl() {
    }

    public RunContextImpl(NElement element) {
        NObjectElement o = element.asObject().get();
        this.pc = o.getIntValue("pc").orElse(-1);
        this.returnPc = o.getIntValue("returnPc").orNull();
        for (NPairElement p : o.getObject("params").orElse(NObjectElement.ofEmpty()).namedPairs()) {
            this.params.put(p.key().asStringValue().orNull(), NElements.of().toSimple(p.value())); // NEW
        }
        for (NPairElement p : o.getObject("state").orElse(NObjectElement.ofEmpty()).namedPairs()) {
            this.state.put(p.key().asStringValue().orNull(), NElements.of().toSimple(p.value())); // NEW
        }
        for (NElement item : o.getArray("todo").get().children()) {
            this.todo.add(NaruStatementHelper.of(item));
        }
    }

    @Override
    public String getRunningRoutine() {
        return runningRoutine;
    }

    public RunContextImpl setRunningRoutine(String runningRoutine) {
        this.runningRoutine = runningRoutine;
        return this;
    }

    public String getRoutine() {
        return routine;
    }

    public RunContextImpl setRoutine(String routine) {
        this.routine = routine;
        return this;
    }

    public int pc() {
        return pc;
    }

    public RunContext pc(int pc) {
        this.pc = pc;
        return this;
    }

    public RunContextImpl setReturnPc(Integer returnPc) {
        this.returnPc = returnPc;
        return this;
    }

    public Integer returnPc() {
        return returnPc;
    }

    public Map<String, Object> params() {
        return params;
    }

    @Override
    public void setState(String key, Object value) {
        state.put(key, value);
    }

    @Override
    public Object getState(String key) {
        return state.get(key);
    }

    @Override
    public boolean hasState(String key) {
        return state.containsKey(key);
    }

    @Override
    public void bindParam(String name, Object value) {
        params.put(name, value);
    }

    @Override
    public Object getParam(String name) {
        return params.get(name);
    }

    @Override
    public boolean hasParam(String name) {
        return params.containsKey(name);
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder pb = NObjectElementBuilder.of(); // NEW: params builder
        for (Map.Entry<String, Object> e : params.entrySet()) {
            pb.set(e.getKey(), NElements.of().toElement(e.getValue()));
        }
        NObjectElementBuilder ps = NObjectElementBuilder.of(); // NEW: params builder
        for (Map.Entry<String, Object> e : state.entrySet()) {
            ps.set(e.getKey(), NElements.of().toElement(e.getValue()));
        }
        return NElement.ofObjectBuilder()
                .set("pc", pc)
                .set("returnPc", returnPc)           // NEW
                .set("params", pb.build())           // NEW
                .set("state", ps.build())           // NEW
                .set("todo", NElement.ofArray(
                        todo.stream().map(x -> x.toElement()).toArray(NElement[]::new)
                )).build();
    }
}
