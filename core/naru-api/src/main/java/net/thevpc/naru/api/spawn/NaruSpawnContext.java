package net.thevpc.naru.api.spawn;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;

import java.util.List;
import java.util.Map;

/**
 * The spawn seam: what an extension or directive sees and contributes to while a task is
 * being spawned.
 * <p>
 * {@code NaruSessionExtension.onSpawn(NaruSpawnContext)} is called before anything is
 * resolved, with the parent, the strategy, the resolved named policy and the target
 * contract visible and with {@link #seedTag(NaruSpawnSource) seed} methods to contribute
 * defaults. Resolution then applies, in this order (later wins, lowest to highest
 * precedence): extension/spawn-kind defaults → named policy → call-site flags → contract
 * validation.
 * <p>
 * After the spawn is resolved and the child task is created and registered,
 * {@code NaruSessionExtension.onSpawned(NaruSession, NaruTask, NaruSpawnContext)} is
 * called with this same context carrying the {@link #resolution()}: the resolved sets
 * with the source of every item, so an extension (such as skills) can react to what
 * the child actually received.
 * <p>
 * There is deliberately <b>no</b> {@code child()} accessor here: before resolution no
 * child exists to hand out, and after resolution the child is the explicit second
 * argument of {@code onSpawned}. Spawn kind is intentionally a {@code String} (see
 * {@link #spawnKind()}) rather than an enum — extension defaults are keyed by kind,
 * and kinds are an open set contributed by extensions, so a closed enum would force
 * the core to know every kind.
 */
public interface NaruSpawnContext {

    /** The session the task is being spawned in. */
    NaruSession session();

    /**
     * The parent task, or null when the spawned task is a root (no parent). A spawn is a
     * child creation, so root spawns (the session's first task) are rare but possible.
     */
    NaruTask parent();

    /** The spec the spawn was requested with. */
    NaruTaskSpec spec();

    /**
     * What is being spawned: e.g. {@code "routine"}, {@code "agent"}, {@code "model"},
     * {@code "script"} or {@code "directive"}. Spawn-kind is how extension defaults can
     * differ per kind.
     */
    String spawnKind();

    /** The effective context strategy at the time of {@code onSpawn}, before resolution. */
    NaruSpawnStrategy strategy();

    /** The window size when {@link #strategy()} is {@link NaruSpawnStrategy#WINDOW}. */
    int windowTurns();

    /** The resolved named policy, or null when none was referenced. */
    NaruSpawnPolicy policy();

    /** The resolved target contract, or null when the target declares none. */
    NaruSpawnContract contract();

    // ── seeding (defaults, from extensions and directives) ─────────────────

    /** Adds a tag to the resolved tag set, recorded with the given provenance. */
    NaruSpawnContext seedTag(String tag, NaruSpawnSource source);

    /** Asks to revoke a tag from the resolved tag set, recorded with the given provenance. */
    NaruSpawnContext seedRevokeTag(String tag, NaruSpawnSource source);

    /** Adds a tool exclusion to the resolved exclusion set, with provenance. */
    NaruSpawnContext seedExclusion(String tool, NaruSpawnSource source);

    /** Adds a skill name to the resolved skill set, with provenance. */
    NaruSpawnContext seedSkill(String skill, NaruSpawnSource source);

    /** Seeds one environment variable for the spawned task, with provenance. */
    NaruSpawnContext seedEnv(String key, Object value, NaruSpawnSource source);

    /** Marks a kind of parent state to inherit as a spawn-time snapshot, with provenance. */
    NaruSpawnContext seedInherit(NaruSpawnInherit kind, NaruSpawnSource source);

    /**
     * Overrides the context strategy (and window). The winning contribution is the highest
     * precedence one; the last {@code DEFAULT} contribution wins among defaults.
     */
    NaruSpawnContext seedStrategy(NaruSpawnStrategy strategy, int windowTurns, NaruSpawnSource source);

    // ── resolved view ──────────────────────────────────────────────────────

    /**
     * The resolved plan: populated once the spawn is resolved. Returns an immutable
     * snapshot of every seed that survived resolution and the provenance of each item.
     * Before resolution this is an empty plan.
     */
    NaruSpawnResolution resolution();

    /** Convenience for the resolved tag names. */
    List<String> resolvedTagNames();
}