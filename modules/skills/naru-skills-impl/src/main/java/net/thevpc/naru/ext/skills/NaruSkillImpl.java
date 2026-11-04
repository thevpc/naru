package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.spawn.NaruToolTagExpression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

class NaruSkillImpl implements NaruSkill {
    private final String name;
    private final String sourceName;
    private final String originRoot;
    private final String baseDir;
    private final NaruVisibility visibility;
    private final boolean shadowed;
    private final String description;
    private final String contentHash;
    private final Map<String, Object> frontMatter;
    private final List<String> warnings;
    private final List<String> lines = new ArrayList<>();
    private final NaruToolTagExpression requires;
    private final NaruSkillRoot root;

    NaruSkillImpl(String name, NaruVisibility visibility, boolean shadowed,
                  String sourceName, String originRoot, String baseDir, String description,
                  String contentHash, Map<String, Object> frontMatter,
                  NaruToolTagExpression requires, List<String> warnings, List<String> lines) {
        this(name, visibility, shadowed, sourceName, originRoot, baseDir, description,
                contentHash, frontMatter, requires, warnings, lines, null);
    }

    NaruSkillImpl(String name, NaruVisibility visibility, boolean shadowed,
                  String sourceName, String originRoot, String baseDir, String description,
                  String contentHash, Map<String, Object> frontMatter,
                  NaruToolTagExpression requires, List<String> warnings, List<String> lines,
                  NaruSkillRoot root) {
        this.name = name;
        this.visibility = visibility;
        this.shadowed = shadowed;
        this.sourceName = sourceName;
        this.originRoot = originRoot;
        this.baseDir = baseDir;
        this.root = root;
        this.description = description == null ? "" : description;
        this.contentHash = contentHash;
        this.frontMatter = Collections.unmodifiableMap(
                frontMatter == null ? Map.of() : new LinkedHashMap<>(frontMatter));
        this.requires = requires;
        this.warnings = Collections.unmodifiableList(
                warnings == null ? List.of() : new ArrayList<>(warnings));
        if (lines != null) {
            this.lines.addAll(lines);
        }
    }

    /** A copy of this skill with a recomputed shadowed flag (WP6 effective-root merge). */
    NaruSkillImpl withShadowed(boolean shadowed) {
        return new NaruSkillImpl(name, visibility, shadowed, sourceName, originRoot,
                baseDir, description, contentHash, frontMatter, requires, warnings, lines, root);
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
    public boolean isShadowed() {
        return shadowed;
    }

    @Override
    public String getOriginRoot() {
        return originRoot;
    }

    @Override
    public NaruSkillRoot getRoot() {
        return root;
    }

    @Override
    public String getBaseDir() {
        return baseDir;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getFrontMatter() {
        return frontMatter;
    }

    @Override
    public String getContentHash() {
        return contentHash;
    }

    @Override
    public Set<String> getAllowedTools() {
        Set<String> out = new LinkedHashSet<>();
        Object at = frontMatter.get("allowed-tools");
        if (at instanceof String s) {
            for (String tok : s.trim().split("\\s+")) {
                if (!tok.isEmpty()) {
                    out.add(tok);
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }

    @Override
    public List<String> getWarnings() {
        return warnings;
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
