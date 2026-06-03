package net.thevpc.naru.api.routine;

import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

public interface NaruTaskFrame extends NToElement {
    boolean isInheritVars();
    void setLocalVar(String key, Object value);

    NOptional<Object> getLocalVar(String key);

    void setInternalState(String key, Object value);

    NaruTaskFrame unsetInternalState(String key);

    NaruTaskFrame unsetState(String key);

    NOptional<Object> getInternalState(String key);

    void bindParam(String name, Object value);

    NOptional<Object> getParam(String name);

    Map<String, Object> params();

    Map<String, Object> localVars();

    Integer returnPc();

    NaruTaskFrame pc(int pc);

    int pc();

    String routine();

    String getRunningRoutine();
}
