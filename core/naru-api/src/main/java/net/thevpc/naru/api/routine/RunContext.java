package net.thevpc.naru.api.routine;

import java.util.Map;

public interface RunContext {
    void setState(String key, Object value);

    Object getState(String key);

    boolean hasState(String key);

    // NEW helpers
    void bindParam(String name, Object value);

    Object getParam(String name);

    boolean hasParam(String name);

    Map<String, Object> params();

    Integer returnPc();

    RunContext pc(int pc);
    String getRoutine();
    String getRunningRoutine();
}
