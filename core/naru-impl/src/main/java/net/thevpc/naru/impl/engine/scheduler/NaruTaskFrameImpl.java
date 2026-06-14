package net.thevpc.naru.impl.engine.scheduler;

import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.routine.NaruTaskFrame;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
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
    private NaruStmtResult lastResult = null;          // to resume after /return
    private final Map<String, Object> params = new HashMap<>(); // local param frame
    private final Map<String, Object> localVars = new HashMap<>(); // Local mutable state
    private String runningRoutine;
    private String editRoutine;
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
        for (NPairElement p : o.getObject("localVars").orElse(NObjectElement.ofEmpty()).namedPairs()) {
            this.localVars.put(p.key().asStringValue().orNull(), NElements.of().toSimple(p.value())); // NEW
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
    public String runningRoutine() {
        return runningRoutine;
    }

    public NaruTaskFrameImpl runningRoutine(String runningRoutine) {
        this.runningRoutine = runningRoutine;
        return this;
    }


    public String editRoutine() {
        return editRoutine;
    }

    public NaruTaskFrameImpl editRoutine(String routine) {
        this.editRoutine = routine;
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
        if ("_".equals(name)) {
            return NOptional.of(NaruStmtResult.nonNull(lastResult).successValue());
        }
        if (localVars.containsKey(name)) {
            return NOptional.ofNullable(localVars.get(name));
        }
        return NOptional.ofNamedEmpty(name);
    }

    public NaruStmtResult getLastResult() {
        return NaruStmtResult.nonNull(lastResult);
    }

    @Override
    public NaruTaskFrame lastResult(NaruStmtResult lastResult) {
        this.lastResult = NaruStmtResult.nonNull(lastResult);
        return this;
    }

    @Override
    public Map<String, Object> getAllVars() {
        HashMap<String, Object> e = new HashMap<>();
        e.putAll(params);
        e.putAll(localVars);
        return e;
    }


    @Override
    public NaruTaskFrame unsetLocalVar(String key) {
        localVars.remove(key);
        return this;
    }

    @Override
    public void setParam(String name, Object value) {
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
        NElements ee = NElements.of();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            pb.set(e.getKey(), ee.toElement(e.getValue()));
        }
        NObjectElementBuilder ps = NObjectElementBuilder.of();
        for (Map.Entry<String, Object> e : localVars.entrySet()) {
            ps.set(e.getKey(), ee.toElement(e.getValue()));
        }
        return NElement.ofObjectBuilder()
                .set("pc", pc)
                .set("returnPc", returnPc)
                .set("lastResult", ee.toElement(lastResult))
                .set("inheritVars", inheritVars)
                .set("params", pb.build())
                .set("localVars", ps.build())
                .set("todo", NElement.ofArray(
                        todo.stream().map(x -> x.toElement()).toArray(NElement[]::new)
                ))
                .build();
    }
}
