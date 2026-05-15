package net.thevpc.naru.impl.script;

import net.thevpc.naru.api.agent.NaruScript;

import java.util.Map;
import java.util.TreeMap;

public class NaruScriptImpl implements NaruScript {
    private final String name;
    private final TreeMap<Integer, String> lines = new TreeMap<>();

    public NaruScriptImpl(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void putLine(int lineNumber, String text) {
        lines.put(lineNumber, text);
    }

    @Override
    public void removeLine(int lineNumber) {
        lines.remove(lineNumber);
    }

    @Override
    public void clear() {
        lines.clear();
    }

    @Override
    public TreeMap<Integer, String> getLines() {
        return lines;
    }

    @Override
    public String getFormattedText() {
        if (lines.isEmpty()) {
            return "<empty>";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> entry : lines.entrySet()) {
            sb.append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
