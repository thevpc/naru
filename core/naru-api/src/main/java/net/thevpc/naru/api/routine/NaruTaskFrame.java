package net.thevpc.naru.api.routine;

import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

public interface NaruTaskFrame extends NToElement {
    void setState(String key, Object value);

    NOptional<Object> getState(String key);

    void setInternalState(String key, Object value);

    NaruTaskFrame unsetInternalState(String key);

    NaruTaskFrame unsetState(String key);

    NOptional<Object> getInternalState(String key);

    void bindParam(String name, Object value);

    NOptional<Object> getParam(String name);

    Map<String, Object> params();

    Integer returnPc();

    NaruTaskFrame pc(int pc);
    int pc();

    String routine();

    String getRunningRoutine();
}
