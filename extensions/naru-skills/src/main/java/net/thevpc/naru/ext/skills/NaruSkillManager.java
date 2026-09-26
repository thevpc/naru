package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruResourceInfo;

import java.util.List;

/**
 * Resolves skills by name against the session's project directory.
 * <p>
 * Obtained from {@link NaruSkillsExtension#skills()}, which is session-scoped.
 */
public interface NaruSkillManager {
    /**
     * Metadata for a skill, or null when no such skill exists. The returned name is
     * canonical, so it can be fed back to {@link #findSkill(String)} verbatim.
     */
    NaruResourceInfo findSkillInfo(String name);

    /**
     * Loads a skill, or null when no such skill exists. Names are matched
     * case- and separator-insensitively, so {@code "My Skill"} resolves the file
     * {@code my-skill.md}.
     */
    NaruSkill findSkill(String name);

    /**
     * Every skill visible to this session, one entry per name, with private copies
     * shadowing public ones.
     */
    List<NaruResourceInfo> available();
}
