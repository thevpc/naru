package net.thevpc.naru.impl.routine;

import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.io.NPath;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NaruRoutineManagerImpl implements NaruRoutineManager {
    private final Map<String, NaruRoutine> scripts = new HashMap<>();
    private String currentScriptName = "main";
    private static final Pattern LINE_PATTERN = Pattern.compile("^(\\d+)(?:\\s+(.*))?$");

    public NaruRoutineManagerImpl() {
        scripts.put(currentScriptName, new NaruRoutineImpl(currentScriptName));
    }

    @Override
    public NaruRoutine getRoutine(String name) {
        return scripts.get(name);
    }

    @Override
    public NaruRoutine getCurrentRoutine() {
        return scripts.get(currentScriptName);
    }

    @Override
    public String getCurrentRoutineName() {
        return currentScriptName;
    }

    @Override
    public void switchRoutine(String name) {
        this.currentScriptName = name;
        scripts.putIfAbsent(name, new NaruRoutineImpl(name));
    }

    @Override
    public void putLine(int number, String text) {
        getCurrentRoutine().putLine(number, text);
    }

    @Override
    public void removeLine(int number) {
        getCurrentRoutine().removeLine(number);
    }

    @Override
    public void clearCurrent() {
        getCurrentRoutine().clear();
    }

    @Override
    public String listCurrent() {
        return getCurrentRoutine().getFormattedText();
    }

    @Override
    public void load(String pathStr, NaruSession context) {
        NPath path = context.projectDir().resolve(pathStr);
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
    public void save(String pathStr, NaruSession context)  {
        NPath path = context.projectDir().resolve(pathStr);
        path.writeString(getCurrentRoutine().getFormattedText());
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
