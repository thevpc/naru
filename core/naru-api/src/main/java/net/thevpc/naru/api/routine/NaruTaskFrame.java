package net.thevpc.naru.api.routine;

import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

public interface NaruTaskFrame extends NToElement {
    boolean isInheritVars();

    void setLocalVar(String key, Object value);

    Object getLastResult();

    NOptional<Object> getLocalVar(String key);

    NaruTaskFrame unsetLocalVar(String key);

    void setParam(String name, Object value);

    NOptional<Object> getParam(String name);

    Map<String, Object> params();

    Map<String, Object> localVars();

    Integer returnPc();

    NaruTaskFrame pc(int pc);

    int pc();

    NaruTaskFrame lastResult(NaruStmtResult ret);

    Map<String, Object> getAllVars();

    String runningRoutine();

    NaruTaskFrame editRoutine(String name);

    String editRoutine();
}
