package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NAruVisibility;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

class NaruSkillImpl implements NaruSkill {
    private final String name;
    private final String sourceName;
    private final NAruVisibility visibility;
    private final List<String> lines = new ArrayList<>();

    NaruSkillImpl(String name, NAruVisibility visibility, List<String> lines, String sourceName) {
        this.name = name;
        this.sourceName = sourceName;
        this.visibility = visibility;
        this.lines.addAll(lines);
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    @Override
    public NAruVisibility getVisibility() {
        return visibility;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public List<String> getLines() {
        return lines;
    }

    @Override
    public List<String> getLines(IntPredicate lineFilter) {
        List<String> newOne = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lineFilter == null || lineFilter.test(i)) {
                newOne.add(lines.get(i));
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
        for (String e : lines) {
            sb.append(e).append("\n");
        }
        return sb.toString();
    }

    @Override
    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
