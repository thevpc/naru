package net.thevpc.naru.api.routine;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

public interface NaruTaskFrame extends NToElement {
    boolean isInheritVars();

    void setLocalVar(String key, NElement value);

    NaruStmtResult getLastResult();

    NOptional<NElement> getLocalVar(String key);

    NaruTaskFrame unsetLocalVar(String key);

    void setParam(String name, NElement value);

    NOptional<NElement> getParam(String name);

    Map<String, NElement> params();

    Map<String, NElement> localVars();

    Integer returnPc();

    NaruTaskFrame pc(int pc);

    int pc();

    NaruTaskFrame lastResult(NaruStmtResult ret);

    Map<String, NElement> getAllVars();

    String runningRoutine();

    NaruTaskFrame editRoutine(String name);

    String editRoutine();
}
