package net.thevpc.naru.impl.scheduler;

import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.routine.NaruTaskFrame;
import net.thevpc.naru.impl.stmt.NaruStatementHelper;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NaruTaskFrameImpl implements NaruTaskFrame {
    private int pc = -1; // -1 means not currently executing a script
    public List<NaruStatement> todo = new ArrayList<>();
    private Integer returnPc = null;          // to resume after /return
    private final Map<String, Object> params = new HashMap<>(); // local param frame
    private final Map<String, Object> localVars = new HashMap<>(); // Local mutable state
    private final Map<String, Object> internalState = new HashMap<>(); // used by while etc...
    private String routine;
    private String runningRoutine;
    private boolean inheritVars;

    public NaruTaskFrameImpl() {
    }

    public NaruTaskFrameImpl(NElement element) {
        NObjectElement o = element.asObject().get();
        this.pc = o.getIntValue("pc").orElse(-1);
        this.returnPc = o.getIntValue("returnPc").orNull();
        this.inheritVars = o.getBooleanValue("inheritVars").orNull();
        for (NPairElement p : o.getObject("params").orElse(NObjectElement.ofEmpty()).namedPairs()) {
            this.params.put(p.key().asStringValue().orNull(), NElements.of().toSimple(p.value())); // NEW
        }
        for (NPairElement p : o.getObject("state").orElse(NObjectElement.ofEmpty()).namedPairs()) {
            this.localVars.put(p.key().asStringValue().orNull(), NElements.of().toSimple(p.value())); // NEW
        }
        for (NPairElement p : o.getObject("internalState").orElse(NObjectElement.ofEmpty()).namedPairs()) {
            this.internalState.put(p.key().asStringValue().orNull(), NElements.of().toSimple(p.value())); // NEW
        }
        for (NElement item : o.getArray("todo").get().children()) {
            this.todo.add(NaruStatementHelper.of(item));
        }
    }

    @Override
    public boolean isInheritVars() {
        return inheritVars;
    }

    public NaruTaskFrameImpl inheritVars(boolean inheritVars) {
        this.inheritVars = inheritVars;
        return this;
    }

    @Override
    public String getRunningRoutine() {
        return runningRoutine;
    }

    public NaruTaskFrameImpl runningRoutine(String runningRoutine) {
        this.runningRoutine = runningRoutine;
        return this;
    }


    public String routine() {
        return routine;
    }

    public NaruTaskFrameImpl setRoutine(String routine) {
        this.routine = routine;
        return this;
    }

    public int pc() {
        return pc;
    }

    public NaruTaskFrame pc(int pc) {
        this.pc = pc;
        return this;
    }

    public NaruTaskFrameImpl returnPc(Integer returnPc) {
        this.returnPc = returnPc;
        return this;
    }

    public Integer returnPc() {
        return returnPc;
    }

    public Map<String, Object> params() {
        return new HashMap<>(params);
    }

    @Override
    public Map<String, Object> localVars() {
        return new HashMap<>(localVars);
    }

    @Override
    public void setLocalVar(String key, Object value) {
        localVars.put(key, value);
    }

    @Override
    public NOptional<Object> getLocalVar(String name) {
        if (localVars.containsKey(name)) {
            return NOptional.ofNullable(localVars.get(name));
        }
        return NOptional.ofNamedEmpty(name);
    }

    @Override
    public void setInternalState(String key, Object value) {
        internalState.put(key, value);
    }

    @Override
    public NaruTaskFrame unsetInternalState(String key) {
        internalState.remove(key);
        return this;
    }

    @Override
    public NaruTaskFrame unsetState(String key) {
        localVars.remove(key);
        return this;
    }

    @Override
    public NOptional<Object> getInternalState(String name) {
        if (internalState.containsKey(name)) {
            return NOptional.ofNullable(internalState.get(name));
        }
        return NOptional.ofNamedEmpty(name);
    }

    @Override
    public void bindParam(String name, Object value) {
        params.put(name, value);
    }

    @Override
    public NOptional<Object> getParam(String name) {
        if (params.containsKey(name)) {
            return NOptional.ofNullable(params.get(name));
        }
        return NOptional.ofNamedEmpty(name);
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder pb = NObjectElementBuilder.of();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            pb.set(e.getKey(), NElements.of().toElement(e.getValue()));
        }
        NObjectElementBuilder ps = NObjectElementBuilder.of();
        for (Map.Entry<String, Object> e : localVars.entrySet()) {
            ps.set(e.getKey(), NElements.of().toElement(e.getValue()));
        }
        NObjectElementBuilder ips = NObjectElementBuilder.of();
        for (Map.Entry<String, Object> e : internalState.entrySet()) {
            ips.set(e.getKey(), NElements.of().toElement(e.getValue()));
        }
        return NElement.ofObjectBuilder()
                .set("pc", pc)
                .set("returnPc", returnPc)
                .set("inheritVars", inheritVars)
                .set("params", pb.build())
                .set("state", ps.build())
                .set("internalState", ips.build())
                .set("todo", NElement.ofArray(
                        todo.stream().map(x -> x.toElement()).toArray(NElement[]::new)
                )).build();
    }
}
