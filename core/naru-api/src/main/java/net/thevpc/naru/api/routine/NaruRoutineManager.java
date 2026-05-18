package net.thevpc.naru.api.routine;

import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSession;

import java.util.List;

public interface NaruRoutineManager {
    NaruRoutine getRoutine(String name);

    NaruRoutine getCurrentRoutine();

    String getCurrentRoutineName();

    void switchRoutine(String name);

    void putLine(int number, String text);

    void removeLine(int number);

    void clearCurrent();

    String listCurrent();

    boolean tryParseLine(String input);

    String findByUuidOrName(String uuidOrName);

    List<NaruResourceInfo> list();
}
