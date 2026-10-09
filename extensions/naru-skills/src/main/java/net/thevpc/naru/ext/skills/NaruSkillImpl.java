package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.spawn.NaruToolTagExpression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

class NaruSkillImpl implements NaruSkill {
    private final String name;
    private final String sourceName;
    private final NaruVisibility visibility;
    private final List<String> lines = new ArrayList<>();
    private final NaruToolTagExpression requires;

    NaruSkillImpl(String name, NaruVisibility visibility, List<String> lines, String sourceName) {
        this(name, visibility, lines, sourceName, null);
    }

    NaruSkillImpl(String name, NaruVisibility visibility, List<String> lines, String sourceName,
                  NaruToolTagExpression requires) {
        this.name = name;
        this.sourceName = sourceName;
        this.visibility = visibility;
        this.requires = requires;
        this.lines.addAll(lines);
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    @Override
    public NaruVisibility getVisibility() {
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

    @Override
    public NaruToolTagExpression getRequires() {
        return requires;
    }

    @Override
    public Set<String> getRequiredTags() {
        if (requires == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(requires.positiveTagNames()));
    }
}
