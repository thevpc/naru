package net.thevpc.naru.api.agent;

import java.io.IOException;

public interface NaruScriptManager {
    NaruScript getScript(String name);

    NaruScript getCurrentScript();

    String getCurrentScriptName();

    void switchScript(String name);

    void putLine(int number, String text);

    void removeLine(int number);

    void clearCurrent();

    String listCurrent();

    void load(String pathStr, NaruAgentContext context) throws IOException;

    void save(String pathStr, NaruAgentContext context) throws IOException;

    boolean tryParseLine(String input);
}
