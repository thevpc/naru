package net.thevpc.naru.api.routine;

import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.util.List;

public interface NaruRoutineManager {
    NOptional<NaruRoutine> routine(String name);

    NOptional<NaruRoutine> unnumberedRoutine(NPath path);

    NOptional<NaruRoutine> routineOrUnnumberedRoutine(String nameOrPath, NaruTask task);

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
