package net.thevpc.naru.impl.ia.skill;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.skills.NaruSkill;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NNameFormat;

import java.util.*;
import java.util.stream.Collectors;

public class NaruSkillManagerImpl implements NaruSkillManager {
    private final NaruSessionImpl session;
    private final ConflictResolution conflictResolution = ConflictResolution.PRIVATE_WINS;

    public NaruSkillManagerImpl(NaruSessionImpl session) {
        this.session = session;
    }

    @Override
    public List<NaruResourceInfo> available() {
        HashMap<String, NaruResourceInfo> a = new HashMap<>();
        for (NPath p : skillsDir(NAruVisibility.PUBLIC).list().stream().filter(x -> isValidSkillName(x)).collect(Collectors.toList())) {
            String goodName = NNameFormat.LOWER_KEBAB_CASE.format(skillNamFromPath(p));
            NaruResourceInfo s = a.computeIfAbsent(goodName, x -> new NaruResourceInfo().setName(goodName));
            s.setModificationInstant(p.creationInstant());
            s.setModificationInstant(p.lastModifiedInstant());
            s.setVisibility(NAruVisibility.PUBLIC);
        }
        for (NPath p : skillsDir(NAruVisibility.PRIVATE).list().stream().filter(x -> isValidSkillName(x)).collect(Collectors.toList())) {
            String goodName = NNameFormat.LOWER_KEBAB_CASE.format(p.name().substring(0, p.name().length() - 3));
            NaruResourceInfo s = a.computeIfAbsent(goodName, x -> new NaruResourceInfo().setName(goodName));
            //private wins
            if (conflictResolution == ConflictResolution.PRIVATE_WINS) {
                s.setModificationInstant(p.creationInstant());
                s.setModificationInstant(p.lastModifiedInstant());
                s.setVisibility(NAruVisibility.PRIVATE);
            } else if (conflictResolution == ConflictResolution.MERGE) {
                if (s.getCreationInstant() == null || s.getCreationInstant().isAfter(p.creationInstant())) {
                    s.setModificationInstant(p.creationInstant());
                }
                if (s.getModificationInstant() == null || s.getModificationInstant().isBefore(p.lastModifiedInstant())) {
                    s.setModificationInstant(p.lastModifiedInstant());
                }
                NAruVisibility mode = s.getMode();
                if (mode == null) {
                    mode = NAruVisibility.PRIVATE;
                }
                switch (mode) {
                    case PUBLIC: {
                        s.setVisibility(NAruVisibility.MIXED);
                        break;
                    }
                    default: {
                        s.setVisibility(NAruVisibility.PRIVATE);
                    }
                }

            }
        }
        return a.values().stream().sorted((o1, o2) -> {
            int x = o1.getName().compareTo(o2.getName());
            if (x != 0) {
                return 0;
            }
            return o2.getModificationInstant().compareTo(o1.getModificationInstant());
        }).collect(Collectors.toList());
    }

    private static boolean isValidSkillName(NPath x) {
        return x.name().endsWith(".md");
    }

    private static String skillNamFromPath(NPath p) {
        return p.name().substring(0, p.name().length() - 3);
    }


    private NPath skillsDir(NAruVisibility visibility) {
        if (visibility == NAruVisibility.PUBLIC) {
            return session.projectDir().resolve(".naru/skills/");
        }
        return session.projectDir().resolve(".naru/local/skills/");
    }

    private SkillFiles findSkillFiles(String name) {
        if (NBlankable.isBlank(name)) {
            return null;
        }
        String name2 = NNameFormat.LOWER_KEBAB_CASE.format(name);
        NPath publicSkill = skillsDir(NAruVisibility.PUBLIC).resolve(name2 + ".md");
        if (!publicSkill.exists()) {
            publicSkill = skillsDir(NAruVisibility.PUBLIC).stream().filter(x -> isValidSkillName(x))
                    .filter(x -> NNameFormat.equalsIgnoreFormat(skillNamFromPath(x), name2))
                    .findFirst().orNull();
        }
        NPath privateSkill = skillsDir(NAruVisibility.PRIVATE).resolve(name2 + ".md");
        if (!privateSkill.exists()) {
            privateSkill = skillsDir(NAruVisibility.PRIVATE).stream().filter(x -> isValidSkillName(x))
                    .filter(x -> NNameFormat.equalsIgnoreFormat(skillNamFromPath(x), name2))
                    .findFirst().orNull();
        }
        SkillFiles sf = new SkillFiles();
        sf.name = name;
        if (publicSkill != null && publicSkill.isRegularFile()) {
            sf.publicSkill = publicSkill;
        }
        if (privateSkill != null && privateSkill.isRegularFile()) {
            sf.privateSkill = privateSkill;
        }
        if (sf.publicSkill == null && sf.privateSkill == null) {
            return null;
        }
        if (sf.publicSkill != null && sf.privateSkill != null) {
            switch (conflictResolution){
                case PRIVATE_WINS: {
                    sf.mode = NAruVisibility.PRIVATE;
                    sf.publicSkill = null;
                    break;
                }
                case MERGE: {
                    sf.mode = NAruVisibility.MIXED;
                    break;
                }
            }
        } else if (sf.publicSkill != null) {
            sf.mode = NAruVisibility.PUBLIC;
        } else if (sf.privateSkill != null) {
            sf.mode = NAruVisibility.PRIVATE;
        }
        return sf;
    }

    @Override
    public NaruResourceInfo findSkillInfo(String name) {
        SkillFiles sf = findSkillFiles(name);
        if (sf == null) {
            return null;
        }
        NaruResourceInfo s = new NaruResourceInfo();
        s.setName(sf.name);

        if (sf.publicSkill != null && sf.privateSkill != null) {
            switch (conflictResolution){
                case PRIVATE_WINS: {
                    s.setCreationInstant(sf.privateSkill.creationInstant());
                    s.setModificationInstant(sf.privateSkill.lastModifiedInstant());
                    s.setVisibility(NAruVisibility.PRIVATE);
                    break;
                }
                case MERGE: {
                    NPath p = sf.publicSkill;
                    if (s.getCreationInstant() == null || s.getCreationInstant().isAfter(p.creationInstant())) {
                        s.setCreationInstant(p.creationInstant());
                    }
                    if (s.getModificationInstant() == null || s.getModificationInstant().isBefore(p.lastModifiedInstant())) {
                        s.setModificationInstant(p.lastModifiedInstant());
                    }
                    p = sf.privateSkill;
                    if (s.getCreationInstant() == null || s.getCreationInstant().isAfter(p.creationInstant())) {
                        s.setModificationInstant(p.creationInstant());
                    }
                    if (s.getModificationInstant() == null || s.getModificationInstant().isBefore(p.lastModifiedInstant())) {
                        s.setModificationInstant(p.lastModifiedInstant());
                    }
                    s.setVisibility(NAruVisibility.MIXED);
                    break;
                }
            }
        }else {
            if (sf.publicSkill != null) {
                s.setCreationInstant(sf.publicSkill.creationInstant());
                s.setModificationInstant(sf.publicSkill.lastModifiedInstant());
                s.setVisibility(NAruVisibility.PUBLIC);
            }
            if (sf.privateSkill != null) {
                s.setCreationInstant(sf.privateSkill.creationInstant());
                s.setModificationInstant(sf.privateSkill.lastModifiedInstant());
                s.setVisibility(NAruVisibility.PRIVATE);
            }
        }
        return s;
    }

    @Override
    public NaruSkill findSkill(String name) {
        SkillFiles sf = findSkillFiles(name);
        if (sf == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        Set<String> validNames=new HashSet<>();
        if (sf.publicSkill != null) {
            validNames.add(sf.publicSkill.toString());
            lines.addAll(sf.publicSkill.lines().collect(Collectors.toList()));
        }
        if (sf.privateSkill != null) {
            validNames.add(sf.privateSkill.toString());
            lines.addAll(sf.privateSkill.lines().collect(Collectors.toList()));
        }
        return new NaruSkillImpl(sf.name, sf.mode, lines,validNames.size()==1?validNames.stream().findFirst().get():validNames.toString());
    }

    private enum ConflictResolution {
        PRIVATE_WINS,
        MERGE,
    }

    private static class SkillFiles {
        String name;
        NAruVisibility mode;
        NPath publicSkill;
        NPath privateSkill;
    }
}
