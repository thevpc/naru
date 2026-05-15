package net.thevpc.naru.api.agent;

import java.util.TreeMap;

public interface NaruScript {
    String getName();

    void putLine(int lineNumber, String text);

    void removeLine(int lineNumber);

    void clear();

    TreeMap<Integer, String> getLines();

    String getFormattedText();

    boolean isEmpty();
}
