package net.thevpc.naru.ext.skills;

/**
 * How a skill is laid out on disk, v2.
 */
public enum NaruSkillLayout {
    /**
     * The legacy single-file form: {@code <root>/<name>.md}. The flat file's first
     * paragraph is used as the description when the front-matter has none.
     */
    FLAT,

    /**
     * The open-standard directory form: {@code <root>/<name>/SKILL.md}, the folder name
     * being the skill name.
     */
    FOLDER
}