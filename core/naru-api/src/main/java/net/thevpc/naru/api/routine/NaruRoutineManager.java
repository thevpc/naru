package net.thevpc.naru.api.routine;

import net.thevpc.naru.api.agent.NaruSession;

public interface NaruRoutineManager {
    NaruRoutine getRoutine(String name);

    NaruRoutine getCurrentRoutine();

    String getCurrentRoutineName();

    void switchRoutine(String name);

    void putLine(int number, String text);

    void removeLine(int number);

    void clearCurrent();

    String listCurrent();

    void load(String pathStr, NaruSession context);

    void save(String pathStr, NaruSession context);

    boolean tryParseLine(String input);
}
