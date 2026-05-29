package net.thevpc.naru.api.routine;

import net.thevpc.naru.api.agent.NAruVisibility;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;

public interface NaruRoutine {
    String uuid();

    NAruVisibility getVisibility();

    String getName();

    void putLine(int lineNumber, String text);

    boolean removeLine(int lineNumber);

    int clear();

    TreeMap<Integer, String> getLinesSet();
    List<IndexedLine> getIndexedLines();
    int firstIndex();
    int nextLineIndex(int currentLineIndex);


    TreeMap<Integer, String> getLinesSet(IntPredicate lineFilter);

    String getFormattedText();

    boolean isEmpty();

    Map<String, SubroutineDef> getSubroutines();

    String getLine(int n);
}
