package net.thevpc.naru.api.routine;

import net.thevpc.naru.api.agent.SubroutineDef;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;

public interface NaruRoutine {
    String getName();

    void putLine(int lineNumber, String text);

    boolean removeLine(int lineNumber);

    int clear();

    TreeMap<Integer, String> getLines();

    TreeMap<Integer, String> getLines(IntPredicate lineFilter);

    String getFormattedText();

    boolean isEmpty();

    Map<String, SubroutineDef> getSubroutines();
}
