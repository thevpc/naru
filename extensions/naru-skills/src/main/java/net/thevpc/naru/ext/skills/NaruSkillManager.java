package net.thevpc.naru.ext.skills;

import java.util.List;

/**
 * Resolves skills by name against the session's project directory.
 * <p>
 * Obtained from {@link NaruSkillsExtension#skills()}, which is session-scoped.
 * <p>
 * The manager keeps a <em>discovery snapshot</em>: {@code available()} reflects what was
 * read when the session opened or the last {@link #reload()} ran, and
 * {@link #findSkill(String)} serves snapshots from that cache — never the disk. This is
 * what makes request-time contribution disk-free. {@code /skill reload} refreshes the
 * snapshot (deliberately applying disk changes), and {@code /skill doctor} compares the
 * cached content hash against the current file to report silent edits instead of applying
 * them.
 */
public interface NaruSkillManager {
    /**
     * Every skill in the discovery snapshot, one entry per name, sorted by name. A private
     * copy shadows a public one (no merge) and {@link NaruSkill#isShadowed()} tells which
     * rows are shadowed.
     */
    List<NaruSkill> available();

    /**
     * Returns the snapshot for a skill, or null when the discovery snapshot has no such
     * name. Names are matched case- and separator-insensitively, so
     * {@code "My Skill"} resolves {@code my-skill.md}. Never touches the disk.
     */
    NaruSkill findSkill(String name);

    /**
     * Refreshes a single skill from disk — the targeted form of {@link #reload()} used by
     * {@code /skill load} so a file created after the session opened can be loaded without
     * re-scanning everything. Returns the new snapshot, or null when the skill now
     * does not exist.
     */
    NaruSkill reload(String name);

    /**
     * Reads a skill from disk right now <em>without</em> touching the discovery snapshot.
     * This is what {@code /skill doctor} uses to compare the snapshot's content hash with
     * the current file: a report can flag a silent change without applying it.
     */
    NaruSkill read(String name);

    /**
     * Re-scans both skill roots and re-reads every skill file, replacing the discovery
     * snapshot. Content hashes are taken afresh, so a file edited since it was loaded is
     * now the truth until it changes again. This is how {@code /skill reload} explicitly
     * applies disk changes.
     */
    void reload();
}