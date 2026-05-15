package net.thevpc.naru.impl.script;

import net.thevpc.naru.api.agent.NaruAgentContext;
import net.thevpc.naru.api.agent.NaruScript;
import net.thevpc.naru.api.agent.NaruScriptManager;
import net.thevpc.nuts.io.NPath;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NaruScriptManagerImpl implements NaruScriptManager {
    private final Map<String, NaruScript> scripts = new HashMap<>();
    private String currentScriptName = "main";
    private static final Pattern LINE_PATTERN = Pattern.compile("^(\\d+)(?:\\s+(.*))?$");

    public NaruScriptManagerImpl() {
        scripts.put(currentScriptName, new NaruScriptImpl(currentScriptName));
    }

    @Override
    public NaruScript getScript(String name) {
        return scripts.get(name);
    }

    @Override
    public NaruScript getCurrentScript() {
        return scripts.get(currentScriptName);
    }

    @Override
    public String getCurrentScriptName() {
        return currentScriptName;
    }

    @Override
    public void switchScript(String name) {
        this.currentScriptName = name;
        scripts.putIfAbsent(name, new NaruScriptImpl(name));
    }

    @Override
    public void putLine(int number, String text) {
        getCurrentScript().putLine(number, text);
    }

    @Override
    public void removeLine(int number) {
        getCurrentScript().removeLine(number);
    }

    @Override
    public void clearCurrent() {
        getCurrentScript().clear();
    }

    @Override
    public String listCurrent() {
        return getCurrentScript().getFormattedText();
    }

    @Override
    public void load(String pathStr, NaruAgentContext context) throws IOException {
        NPath path = context.getProjectDir().resolve(pathStr);
        String text = path.readString();
        clearCurrent();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) {
                Matcher m = LINE_PATTERN.matcher(line);
                if (m.matches()) {
                    int num = Integer.parseInt(m.group(1));
                    String content = m.group(2) != null ? m.group(2) : "";
                    putLine(num, content);
                }
            }
        }
    }

    @Override
    public void save(String pathStr, NaruAgentContext context) throws IOException {
        NPath path = context.getProjectDir().resolve(pathStr);
        path.writeString(getCurrentScript().getFormattedText());
    }

    @Override
    public boolean tryParseLine(String input) {
        Matcher m = LINE_PATTERN.matcher(input);
        if (m.matches()) {
            int num = Integer.parseInt(m.group(1));
            String content = m.group(2) != null ? m.group(2).trim() : "";
            if (content.isEmpty()) {
                removeLine(num);
            } else {
                putLine(num, content);
            }
            return true;
        }
        return false;
    }
}
