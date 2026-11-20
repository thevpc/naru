package net.thevpc.naru.ext.skills;

import java.util.Objects;

/**
 * One copy of a skill in a listing that shows every root, not just the winner (WP6).
 * <p>
 * {@link NaruSkillManager#available()} collapses copies of the same name to the single
 * winner; this type keeps the losers visible so a user can see that a NARU-native skill
 * shadowed an identically named foreign one, or that a private copy shadowed the public
 * one. {@link #shadowed()} is true exactly for the copies that lost.
 */
public final class NaruSkillEntry {
    private final NaruSkill skill;
    private final NaruSkillRoot root;
    private final boolean shadowed;

    public NaruSkillEntry(NaruSkill skill, NaruSkillRoot root, boolean shadowed) {
        this.skill = Objects.requireNonNull(skill, "skill");
        this.root = root;
        this.shadowed = shadowed;
    }

    public NaruSkill skill() {
        return skill;
    }

    public NaruSkillRoot root() {
        return root;
    }

    /** True when another copy of the same name has a stronger root. */
    public boolean shadowed() {
        return shadowed;
    }

    /** True when this is the copy {@link NaruSkillManager#available()} returns. */
    public boolean winner() {
        return !shadowed;
    }

    @Override
    public String toString() {
        return (shadowed ? "shadowed " : "") + skill.getName() + " @ " + (root == null ? "?" : root);
    }
}
