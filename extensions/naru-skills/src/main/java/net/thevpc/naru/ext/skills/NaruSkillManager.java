package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.task.NaruTask;

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
 * <p>
 * The task-aware overloads add the folder-scoped roots for the task's working directory
 * (WP6): those are read live, because they depend on where the task is working.
 */
public interface NaruSkillManager {
    /**
     * Every skill in the discovery snapshot, one entry per name, sorted by name. A private
     * copy shadows a public one (no merge) and {@link NaruSkill#isShadowed()} tells which
     * rows are shadowed.
     */
    List<NaruSkill> available();

    /**
     * The winning copies for a task's effective root set, including the folder-scoped
     * {@code .naru/skills} roots between the project directory and the task's working
     * directory. Sorted by name.
     */
    List<NaruSkill> available(NaruTask task);

    /**
     * Returns the snapshot for a skill, or null when the discovery snapshot has no such
     * name. Names are matched case- and separator-insensitively, so
     * {@code "My Skill"} resolves {@code my-skill.md}. Never touches the disk.
     */
    NaruSkill findSkill(String name);

    /**
     * The winning copy for a name in the task's effective root set, folder roots included.
     * Folder entries are read from disk; base entries come from the snapshot.
     */
    NaruSkill findSkill(NaruTask task, String name);

    /**
     * Every copy of every name in a task's effective root set, the losers included and
     * marked shadowed (WP6). This is what a listing uses so a shadowed copy stays visible.
     */
    List<NaruSkillEntry> entries(NaruTask task);

    /**
     * The ordered roots effective for a task, untrusted foreign roots included (marked) so
     * a listing can offer them for trust.
     */
    List<NaruSkillRoot> roots(NaruTask task);

    /**
     * Records a trust decision for a foreign root and reloads the snapshot so the change
     * takes effect. Returns true when the persisted state changed.
     */
    boolean trust(NaruSkillRoot root, boolean trusted);

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