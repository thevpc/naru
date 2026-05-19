package net.thevpc.naru.impl.skill;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.skills.NaruSkill;

import java.util.*;
import java.util.function.IntPredicate;

public class NaruSkillImpl implements NaruSkill {
    private final String name;
    private NAruVisibility visibility;
    private final List<String> lines = new ArrayList<>();

    public NaruSkillImpl(String name, NAruVisibility visibility, List<String> lines) {
        this.name = name;
        this.visibility = visibility;
        this.lines.addAll(lines);
    }


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
            String e = lines.get(i);
            if (lineFilter == null || lineFilter.test(i)) {
                newOne.add(e);
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
        for (int i = 0; i < lines.size(); i++) {
            String e = lines.get(i);
            sb.append(e).append("\n");
        }
        return sb.toString();
    }

    @Override
    public boolean isEmpty() {
        return lines.isEmpty();
    }


}
