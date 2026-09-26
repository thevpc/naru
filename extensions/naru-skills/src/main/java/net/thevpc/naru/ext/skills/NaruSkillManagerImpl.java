package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NNameFormat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filesystem-backed skill resolution.
 * <p>
 * A public skill lives at {@code <project>/.naru/skills/<name>.md} and a private one at
 * {@code <project>/.naru/local/skills/<name>.md}. Private shadows public: a project
 * checking in a public copy cannot override a developer's local edit, so the local file
 * wins outright rather than the two being concatenated.
 * <p>
 * Depends only on {@link NaruSession#projectDir()}, so this extension needs nothing from
 * {@code naru-impl}.
 */
class NaruSkillManagerImpl implements NaruSkillManager {
    private final NaruSession session;

    NaruSkillManagerImpl(NaruSession session) {
        this.session = session;
    }

    @Override
    public List<NaruResourceInfo> available() {
        Map<String, NaruResourceInfo> a = new HashMap<>();
        for (NPath p : skillFiles(NAruVisibility.PUBLIC)) {
            String goodName = NNameFormat.LOWER_KEBAB_CASE.format(skillNameFromPath(p));
            NaruResourceInfo s = a.computeIfAbsent(goodName, x -> new NaruResourceInfo().setName(goodName));
            s.setVisibility(NAruVisibility.PUBLIC);
            s.setCreationInstant(p.creationInstant());
            s.setModificationInstant(p.lastModifiedInstant());
        }
        for (NPath p : skillFiles(NAruVisibility.PRIVATE)) {
            String goodName = NNameFormat.LOWER_KEBAB_CASE.format(skillNameFromPath(p));
            NaruResourceInfo s = a.computeIfAbsent(goodName, x -> new NaruResourceInfo().setName(goodName));
            // private wins
            s.setVisibility(NAruVisibility.PRIVATE);
            s.setCreationInstant(p.creationInstant());
            s.setModificationInstant(p.lastModifiedInstant());
        }
        return a.values().stream()
                .sorted(Comparator.comparing(NaruResourceInfo::getName))
                .collect(Collectors.toList());
    }

    private static boolean isValidSkillName(NPath x) {
        return x.name().endsWith(".md");
    }

    private static String skillNameFromPath(NPath p) {
        return p.name().substring(0, p.name().length() - 3);
    }

    private NPath skillsDir(NAruVisibility visibility) {
        if (visibility == NAruVisibility.PUBLIC) {
            return session.projectDir().resolve(".naru/skills/");
        }
        return session.projectDir().resolve(".naru/local/skills/");
    }

    private List<NPath> skillFiles(NAruVisibility visibility) {
        NPath dir = skillsDir(visibility);
        if (!dir.isDirectory()) {
            return List.of();
        }
        return dir.stream().filter(NaruSkillManagerImpl::isValidSkillName).collect(Collectors.toList());
    }

    /**
     * Resolves a name to the file (or pair of files) that define it, or null when the
     * skill does not exist. Returns the canonical name alongside, so callers never echo
     * back the spelling the user happened to type.
     */
    private SkillFiles findSkillFiles(String name) {
        if (NBlankable.isBlank(name)) {
            return null;
        }
        String canonical = NNameFormat.LOWER_KEBAB_CASE.format(name.trim());
        SkillFiles sf = new SkillFiles();
        sf.name = canonical;

        NPath publicSkill = resolveFile(NAruVisibility.PUBLIC, canonical);
        NPath privateSkill = resolveFile(NAruVisibility.PRIVATE, canonical);

        if (publicSkill != null && privateSkill != null) {
            // private wins: the local file replaces the checked-in one
            sf.mode = NAruVisibility.PRIVATE;
            sf.privateSkill = privateSkill;
        } else if (publicSkill != null) {
            sf.mode = NAruVisibility.PUBLIC;
            sf.publicSkill = publicSkill;
        } else if (privateSkill != null) {
            sf.mode = NAruVisibility.PRIVATE;
            sf.privateSkill = privateSkill;
        } else {
            return null;
        }
        return sf;
    }

    private NPath resolveFile(NAruVisibility visibility, String canonical) {
        NPath exact = skillsDir(visibility).resolve(canonical + ".md");
        if (exact.isRegularFile()) {
            return exact;
        }
        // fall back to a case/separator-insensitive match, so "MySkill" finds "my-skill.md"
        return skillFiles(visibility).stream()
                .filter(x -> NNameFormat.equalsIgnoreFormat(skillNameFromPath(x), canonical))
                .findFirst()
                .orElse(null);
    }

    @Override
    public NaruResourceInfo findSkillInfo(String name) {
        SkillFiles sf = findSkillFiles(name);
        if (sf == null) {
            return null;
        }
        NaruResourceInfo s = new NaruResourceInfo();
        s.setName(sf.name);
        NPath effective = sf.publicSkill != null ? sf.publicSkill : sf.privateSkill;
        s.setCreationInstant(effective.creationInstant());
        s.setModificationInstant(effective.lastModifiedInstant());
        s.setVisibility(sf.mode);
        return s;
    }

    @Override
    public NaruSkill findSkill(String name) {
        SkillFiles sf = findSkillFiles(name);
        if (sf == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        Set<String> sources = new HashSet<>();
        if (sf.publicSkill != null) {
            sources.add(sf.publicSkill.toString());
            lines.addAll(sf.publicSkill.lines().collect(Collectors.toList()));
        }
        if (sf.privateSkill != null) {
            sources.add(sf.privateSkill.toString());
            lines.addAll(sf.privateSkill.lines().collect(Collectors.toList()));
        }
        return new NaruSkillImpl(sf.name, sf.mode, lines,
                sources.size() == 1 ? sources.iterator().next() : sources.toString());
    }

    private static class SkillFiles {
        String name;
        NAruVisibility mode;
        NPath publicSkill;
        NPath privateSkill;
    }
}
