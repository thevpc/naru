package net.thevpc.naru.ext.skills;

/**
 * The kind of a skill root, which fixes two things: whether the root is NARU-native or
 * foreign, and whether it lives at the project level or the user level.
 * <p>
 * NARU-native roots ({@code .naru/...}) are always trusted and always win over foreign
 * ones; precedence is decided by the root's {@link NaruSkillRoot#precedence()}, and every
 * native precedence is lower (better) than every foreign one. Foreign roots are opt-in:
 * they are read only after the user trusted them once, and the trust is persisted next to
 * the project ({@code .naru/local/skills-trust.tson}) or the user home
 * ({@code ~/.naru/skills-trust.tson}) depending on {@link #userLevel()}.
 */
public enum NaruSkillRootKind {
    /** {@code <project>/.naru/local/skills}: the project's private, always-winning root. */
    PROJECT_PRIVATE("project-local", "naru", false, false),

    /**
     * {@code <ancestor>/.naru/skills} along the projectDir → workingDir walk. The closest
     * directory wins; the project's own public root is the weakest of the walk (its distance
     * is the number of levels from the task's working directory).
     */
    FOLDER_PUBLIC("folder", "naru", false, false),

    /** {@code ~/.naru/skills}: the user's NARU-native root. */
    USER("user", "naru", true, false),

    /** A foreign project root, e.g. {@code <project>/.claude/skills}. */
    FOREIGN_PROJECT("foreign-project", "foreign", false, true),

    /** A foreign user root, e.g. {@code ~/.claude/skills}. */
    FOREIGN_USER("foreign-user", "foreign", true, true);

    private final String id;
    private final String family;
    private final boolean userLevel;
    private final boolean foreign;

    NaruSkillRootKind(String id, String family, boolean userLevel, boolean foreign) {
        this.id = id;
        this.family = family;
        this.userLevel = userLevel;
        this.foreign = foreign;
    }

    /** Stable id, used as the trust key and in listings. */
    public String id() {
        return id;
    }

    /** The origin family: {@code naru}, or the foreign tool the root belongs to. */
    public String family() {
        return family;
    }

    /** True when the root lives under the user home rather than under the project. */
    public boolean userLevel() {
        return userLevel;
    }

    /** True when the root is foreign and therefore requires an explicit trust decision. */
    public boolean foreign() {
        return foreign;
    }
}
