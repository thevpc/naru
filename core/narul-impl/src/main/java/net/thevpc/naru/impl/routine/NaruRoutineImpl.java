package net.thevpc.naru.impl.routine;

import net.thevpc.naru.api.agent.SubroutineDef;
import net.thevpc.naru.api.routine.NaruRoutine;

import java.util.*;
import java.util.function.IntPredicate;

public class NaruRoutineImpl implements NaruRoutine {
    private String uuid;
    private String name;
    private boolean publicRoutine;
    private final TreeMap<Integer, String> lines = new TreeMap<>();

    public NaruRoutineImpl(String name) {
        this.name = name;
    }

    @Override
    public boolean isPublicRoutine() {
        return publicRoutine;
    }

    @Override
    public NaruRoutine setPublicRoutine(boolean publicRoutine) {
        this.publicRoutine = publicRoutine;
        return this;
    }

    public String uuid() {
        return uuid;
    }

    public NaruRoutineImpl setUuid(String uuid) {
        this.uuid = uuid;
        return this;
    }

    public NaruRoutineImpl setName(String name) {
        this.name = name;
        return this;
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
    public boolean removeLine(int lineNumber) {
        String old = lines.remove(lineNumber);
        return old != null;
    }

    @Override
    public int clear() {
        int old = lines.size();
        lines.clear();
        return old;
    }

    @Override
    public TreeMap<Integer, String> getLines() {
        return lines;
    }

    @Override
    public TreeMap<Integer, String> getLines(IntPredicate lineFilter) {
        TreeMap<Integer, String>  newOne=new TreeMap<>();
        for (Map.Entry<Integer, String> e : lines.entrySet()) {
            Integer k = e.getKey();
            if(lineFilter==null || lineFilter.test(k)){
                newOne.put(k, e.getValue());
            }
        }
        return newOne;
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

    @Override
    public Map<String, SubroutineDef> getSubroutines() {
        Map<String, SubroutineDef> subs = new HashMap<>();
        NavigableMap<Integer, String> lines = getLines();

        Integer subStart = null;
        String subName = null;
        List<String> subParams = null;

        for (Map.Entry<Integer, String> entry : lines.entrySet()) {
            String raw = entry.getValue().trim();
            int lineNum = entry.getKey();

            if (raw.startsWith("/sub ")) {
                // Parse: /sub name param1 param2
                String[] parts = raw.substring(5).trim().split("\\s+");
                subName = parts[0];
                subParams = Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length));
                subStart = lineNum;
            }
            else if (raw.equals("/endsub") && subStart != null) {
                subs.put(subName, new SubroutineDefImpl(subStart, lineNum, subParams));
                subStart = null; subName = null; subParams = null;
            }
        }
        return subs;
    }

}
