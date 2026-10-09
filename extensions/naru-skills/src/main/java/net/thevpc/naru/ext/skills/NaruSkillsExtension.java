package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.api.spawn.NaruSpawnContext;
import net.thevpc.naru.api.spawn.NaruSpawnResolution;
import net.thevpc.naru.api.spawn.NaruSpawnSeed;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NNameFormat;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Makes the skills feature available to the session, without the core knowing that skills
 * exist.
 * <p>
 * The extension owns everything about skills: which skills exist (the manager's discovery
 * snapshot), which are loaded for which task, how they are worded into the prompt, and the
 * persisted selection. {@code naru-api} and {@code naru-impl} never mention this type:
 * removing this jar from the classpath removes the feature, along with {@code /skill} and
 * {@code /context skills}.
 *
 * <h2>Selection (v2): flat per task, ADVERTISED by default, LOADED on request</h2>
 * A task's selection is a flat set of LOADED skill names, with no ancestor walk. Every
 * available skill is {@link NaruSkillState#ADVERTISED} for a task until that task
 * {@code /skill load}s it. Advertising is the progressive-disclosure half of the model:
 * the model sees the skill's name and description but not its body. Loading moves the
 * skill to {@link NaruSkillState#LOADED}, and its full body is injected subject to the
 * {@code requires} gate (evaluated at request-build time, not at spawn).
 * <p>
 * Children are seeded at spawn from the resolved spawn plan (the spawn policy and the
 * contract, which the core has already merged into {@code resolution.skills()}) — loaded
 * skills no longer flow implicitly down the parent chain.
 *
 * <h2>Snapshots and disk changes</h2>
 * Skill content is snapshotted when the session opens (or {@code /skill reload} runs) and
 * served from that snapshot at request time — never re-read per request. A file edited
 * while the session is open is therefore reported by {@code /skill doctor} as changed, not
 * silently applied.
 */
public class NaruSkillsExtension implements NaruSessionExtension {

    public static final String NAME = "skills";

    private static final int SCHEMA_VERSION = 2;
    private static final int SCHEMA_VERSION_1 = 1;

    /**
     * Per-task flat selection: task id to the set of LOADED skill names. Any available
     * skill not in this set is ADVERTISED (the default state).
     */
    private final Map<Long, Set<String>> selection = new TreeMap<>();

    private NaruSkillManager manager;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<NaruSource> sources() {
        return EnumSet.of(NaruSource.SKILL);
    }

    @Override
    public NaruSource source() {
        return NaruSource.SKILL;
    }

    /**
     * Skill resolution, shared with the directives of this extension.
     * <p>
     * The lookup cannot fail in practice: the directive that needs it ships in the same jar
     * as the extension that provides it, and both are registered together.
     */
    public static NaruSkillsExtension skills(NaruSession session) {
        return session.registry().extension(NAME, NaruSkillsExtension.class)
                .orElseThrow(() -> new IllegalStateException(
                        "the skills extension is not installed in this session"));
    }

    /**
     * Lazily bound to the session, because the SPI hands out the extension before the
     * session exists to bind it to.
     */
    public NaruSkillManager skills() {
        if (manager == null) {
            throw new IllegalStateException("skills accessed before the extension was opened");
        }
        return manager;
    }

    @Override
    public void open(NaruSession session) {
        this.manager = new NaruSkillManagerImpl(session);
        this.manager.reload();
    }

    /**
     * A task that has left the session cannot use its selection again, and its entry would
     * otherwise be written to {@code ext/skills.tson} forever. Dropping it here is what
     * keeps that file proportional to the live tasks rather than to every task ever run.
     */
    @Override
    public void onTaskDeregistered(NaruSession session, long taskId) {
        selection.remove(taskId);
    }

    /**
     * Seeds the freshly spawned child from the spawn plan: the resolved {@code --add-skills}
     * names, the spawn policy's skills, and the contract's skills are all already merged by
     * the core into {@link NaruSpawnResolution#skills()}. {@code requires} is deliberately
     * <em>not</em> checked here — decision 4 evaluates it at request-build time, when the
     * task's current tags are the ones that matter.
     */
    @Override
    public void onSpawned(NaruSession session, NaruTask task, NaruSpawnContext context) {
        if (manager == null) {
            return;
        }
        NaruSpawnResolution resolution = context.resolution();
        if (resolution == null || resolution.skills().isEmpty()) {
            return;
        }
        for (NaruSpawnSeed<String> seed : resolution.skills()) {
            // delegates to load(), whose unknown-name path refreshes just that skill from
            // disk: the discovery snapshot may be stale (the skill can be written after the
            // session opened), and a targeted refresh reads nothing else.
            load(task, seed.value());
        }
    }

    @Override
    public boolean isRelevant(NaruTask task) {
        return manager != null && !manager.available().isEmpty();
    }

    // ── contribute: request-build ───────────────────────────────────────────

    /**
     * Builds the SKILL-source messages for one model request:
     * <ul>
     *   <li>an ADVERTISED skill contributes name + description (progressive disclosure);</li>
     *   <li>a LOADED skill whose {@code requires} holds (or that declares none) contributes
     *       its full body;</li>
     *   <li>a LOADED skill whose {@code requires} does not hold contributes a gate note and
     *       is <em>not</em> injected — unsatisfiable (unregistered tag) is reported
     *       separately from unsatisfied;</li>
     *   <li>a LOADED skill that is empty or missing from the discovery snapshot is flagged
     *       by a note instead of silently disappearing.</li>
     * </ul>
     * Everything reads the manager's snapshot; no disk access happens here.
     */
    @Override
    public List<NaruMessage> contribute(NaruTask task) {
        List<NaruMessage> out = new ArrayList<>();
        Set<String> loaded = loadedNames(task);
        Set<String> granted = task.findToolTagNames();
        for (NaruSkill skill : skills().available()) {
            String name = skill.getName();
            if (loaded.contains(name)) {
                NaruRequiresStatus status = requiresStatus(skill, task);
                if (status == NaruRequiresStatus.SATISFIED || status == NaruRequiresStatus.NONE) {
                    if (skill.isEmpty()) {
                        out.add(note(skill, "## SKILL IS EMPTY: " + name.toUpperCase()
                                + "\nloaded but has no body — check the file and /skill reload"));
                    } else {
                        out.add(NaruMessage.user(
                                "## ACTIVE SKILL DIRECTIVE: " + name.toUpperCase() + "\n"
                                        + skill.getFormattedText())
                                .setSourceName(skill.getSourceName()));
                    }
                } else {
                    out.add(gateNote(skill, task, granted, status));
                }
            } else {
                out.add(NaruMessage.user(
                        "## AVAILABLE SKILL: " + name.toUpperCase() + "\n"
                                + (NBlankable.isBlank(skill.getDescription())
                                ? "(no description)" : skill.getDescription())
                                + "\n(activate with /skill load " + name + ")")
                        .setSourceName(skill.getSourceName()));
            }
        }
        for (String name : loaded) {
            if (skills().findSkill(name) == null) {
                out.add(NaruMessage.user(
                        "## SKILL MISSING: " + name.toUpperCase()
                                + "\nselected but no longer on disk — /skill doctor")
                        .setSourceName(NAME + ":" + name));
            }
        }
        return out;
    }

    private static NaruMessage gateNote(NaruSkill skill, NaruTask task, Set<String> granted, NaruRequiresStatus status) {
        String headline = status == NaruRequiresStatus.UNSATISFIABLE
                ? "## SKILL REQUIRES GATE (UNSATISFIABLE): " + skill.getName().toUpperCase()
                : "## SKILL REQUIRES GATE (UNSATISFIED): " + skill.getName().toUpperCase();
        String detail;
        if (status == NaruRequiresStatus.UNSATISFIABLE) {
            detail = "requires " + skill.getRequires()
                    + " which references unregistered tags " + unregisteredTags(skill, task)
                    + " — no task can ever satisfy it; install the tag provider, or fix the skill";
        } else {
            detail = "requires " + skill.getRequires() + " which this task's tags do not satisfy: "
                    + skill.getRequires().violations(granted);
        }
        return NaruMessage.user(headline + "\n" + detail
                + "\n(injected, would violate the requires gate; /skill doctor, or --add-tags/--revoke-tags)")
                .setSourceName(skill.getSourceName());
    }

    private static NaruMessage note(NaruSkill skill, String text) {
        return NaruMessage.user(text).setSourceName(skill.getSourceName());
    }

    /**
     * The request-build verdict for a skill: NONE when it declares no expression,
     * UNSATISFIABLE when the expression references a tag no provider declares (reported
     * separately, because the fix is a provider, not a grant), otherwise SATISFIED or
     * UNSATISFIED against the task's current held tags.
     */
    public NaruRequiresStatus requiresStatus(NaruSkill skill, NaruTask task) {
        if (skill.getRequires() == null) {
            return NaruRequiresStatus.NONE;
        }
        if (!unregisteredTags(skill, task).isEmpty()) {
            return NaruRequiresStatus.UNSATISFIABLE;
        }
        return skill.getRequires().isSatisfiedBy(task.findToolTagNames())
                ? NaruRequiresStatus.SATISFIED
                : NaruRequiresStatus.UNSATISFIED;
    }

    private static Set<String> unregisteredTags(NaruSkill skill, NaruTask task) {
        Set<String> refs = new TreeSet<>(skill.getRequires().positiveTagNames());
        refs.addAll(skill.getRequires().negativeTagNames());
        Set<String> out = new TreeSet<>();
        for (String tag : refs) {
            if (task.session().registry().findAvailableTag(tag).isEmpty()) {
                out.add(tag);
            }
        }
        return out;
    }

    // ── selection: flat per task ───────────────────────────────────────────

    /**
     * The LOADED skill names of a task, flat — no ancestor walk. This is the task's own
     * selection, the only selection it has.
     */
    public Set<String> loadedNames(NaruTask task) {
        Set<String> own = selection.get(task.id());
        return own == null ? Collections.emptySet() : Collections.unmodifiableSet(new TreeSet<>(own));
    }

    /** The state of a skill for a task, LOADED or (by default) ADVERTISED. */
    public NaruSkillState state(NaruTask task, String name) {
        String canonical = canonical(name);
        return canonical != null && loadedNames(task).contains(canonical)
                ? NaruSkillState.LOADED
                : NaruSkillState.ADVERTISED;
    }

    /** LOADED names, sorted — kept for the directives and for {@code /context skills}. */
    public Set<String> activeNames(NaruTask task) {
        return loadedNames(task);
    }

    /**
     * Loads a skill for a task, moving it from ADVERTISED to LOADED.
     *
     * @return true when the skill was not already loaded for this task.
     */
    public boolean load(NaruTask task, String name) {
        String canonical = canonical(name);
        if (canonical == null) {
            return false;
        }
        if (skills().findSkill(canonical) == null) {
            // a file created after the session opened: targeted refresh, no full re-scan
            if (skills().reload(canonical) == null) {
                return false;
            }
        }
        Set<String> own = selection.computeIfAbsent(task.id(), x -> new TreeSet<>());
        return own.add(canonical);
    }

    /**
     * Unloads a skill for a task, moving it back to the ADVERTISED default.
     *
     * @return true when the skill was loaded for this task.
     */
    public boolean unload(NaruTask task, String name) {
        String canonical = canonical(name);
        if (canonical == null) {
            return false;
        }
        Set<String> own = selection.get(task.id());
        if (own == null || !own.remove(canonical)) {
            return false;
        }
        if (own.isEmpty()) {
            selection.remove(task.id());
        }
        return true;
    }

    /**
     * Whether a skill exists on disk, regardless of selection. Used by the directives to
     * tell "no such skill" apart from "not loaded". A file created after the session opened
     * is found via a targeted single-file refresh.
     */
    public boolean exists(String name) {
        String canonical = canonical(name);
        return canonical != null && (skills().findSkill(canonical) != null
                || skills().reload(canonical) != null);
    }

    /** Explicit {@code /skill reload}: rebuild the discovery snapshot from disk. */
    public void reload() {
        skills().reload();
    }

    /** Explicit {@code /skill reload <name>}: refresh one skill from disk. */
    public NaruSkill reload(String name) {
        return name == null ? null : skills().reload(name);
    }

    private static String canonical(String name) {
        if (NBlankable.isBlank(name)) {
            return null;
        }
        String c = NNameFormat.LOWER_KEBAB_CASE.format(name.trim());
        return c.isEmpty() ? null : c;
    }

    // ── persistence: schemaVersion 2 (flat LOADED sets), migrated from v1 ──

    @Override
    public void load(NaruSession session, NElement state) {
        selection.clear();
        if (state == null) {
            return;
        }
        try {
            NObjectElement root = state.asObject().orNull();
            if (root == null) {
                return;
            }
            int version = root.getIntValue("schemaVersion").orElse(0);
            NArrayElement tasks = root.getArray("selection").orNull();
            if (tasks == null) {
                return;
            }
            if (version == SCHEMA_VERSION_1) {
                migrateV1(session, tasks);
            } else if (version == SCHEMA_VERSION) {
                for (NElement t : tasks.children()) {
                    NObjectElement to = t.asObject().orNull();
                    if (to == null) {
                        continue;
                    }
                    Long taskId = to.getLongValue("id").orNull();
                    if (taskId == null || taskId < 0) {
                        continue;
                    }
                    Set<String> loaded = new TreeSet<>();
                    readNames(to.getArray("loaded").orNull(), loaded);
                    if (!loaded.isEmpty()) {
                        selection.put(taskId, loaded);
                    }
                }
            } else {
                throw new NIllegalArgumentException(
                        NMsg.ofC("unsupported skill selection schema version '%s' (expected 1 or 2)", version));
            }
        } catch (NIllegalArgumentException e) {
            throw e;
        } catch (Exception ex) {
            // a selection that will not parse is worth complaining about, but not worth
            // failing the session load over: the user can re-select, and losing the whole
            // conversation to a malformed selection is not a trade anyone wants
            selection.clear();
            throw new NIllegalArgumentException(
                    NMsg.ofC("failed to load skill selection: %s", ex.getMessage(), ex));
        }
    }

    /**
     * Flattens a schemaVersion-1 selection into v2. The old model walked the ancestor chain
     * at read time and let a task mask an ancestor's choice; v2 has only a flat per-task
     * set. To migrate without dropping anything, each task's effective set is recomputed
     * with the old walk (loaded adds, masked removes, nearest ancestor wins) and written as
     * that task's own selection. Masks are honored by the computation, not simply deleted,
     * and inherited-only tasks get their inherited set materialized so they do not fall
     * back to ADVERTISED on upgrade.
     */
    private void migrateV1(NaruSession session, NArrayElement tasks) {
        Map<Long, Map<String, Boolean>> v1 = new LinkedHashMap<>();
        for (NElement t : tasks.children()) {
            NObjectElement to = t.asObject().orNull();
            if (to == null) {
                continue;
            }
            Long taskId = to.getLongValue("id").orNull();
            if (taskId == null || taskId < 0) {
                continue;
            }
            Map<String, Boolean> own = new LinkedHashMap<>();
            readNames(to.getArray("loaded").orNull(), own, Boolean.TRUE);
            readNames(to.getArray("masked").orNull(), own, Boolean.FALSE);
            if (!own.isEmpty()) {
                v1.put(taskId, own);
            }
        }
        if (v1.isEmpty()) {
            return;
        }
        // which tasks to materialize: every recorded task, plus every live task that would
        // have inherited at read time (a descendant whose v1 set is empty)
        LinkedHashSet<Long> targets = new LinkedHashSet<>(v1.keySet());
        for (NaruTask live : liveTasks(session)) {
            if (!v1.containsKey(live.id())) {
                targets.add(live.id());
            }
        }
        for (Long taskId : targets) {
            Set<String> effective = new LinkedHashSet<>();
            List<Long> chain = ancestorChain(session, taskId);
            for (Long id : chain) {
                Map<String, Boolean> own = v1.get(id);
                if (own == null) {
                    continue;
                }
                for (Map.Entry<String, Boolean> e : own.entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue())) {
                        effective.add(e.getKey());
                    } else {
                        effective.remove(e.getKey());
                    }
                }
            }
            if (!effective.isEmpty()) {
                selection.put(taskId, new TreeSet<>(effective));
            }
        }
    }

    private static List<Long> ancestorChain(NaruSession session, long taskId) {
        List<Long> chain = new ArrayList<>();
        Long cursor = taskId;
        int guard = 0;
        while (cursor != null && guard++ < 256) {
            chain.add(cursor);
            NOptional<NaruTask> t = session.findTask(cursor);
            if (t.isEmpty()) {
                break;
            }
            long parent = t.get().parentId();
            cursor = parent < 0 ? null : parent;
        }
        Collections.reverse(chain);
        return chain;
    }

    private static List<NaruTask> liveTasks(NaruSession session) {
        // the session registry does not expose a plain "all tasks" list, so walk the roots
        List<NaruTask> out = new ArrayList<>();
        for (long root : session.findTaskIdsByParent(-1)) {
            NOptional<NaruTask> t = session.findTask(root);
            if (t.isPresent()) {
                out.add(t.get());
                collectDescendants(session, t.get(), out);
            }
        }
        return out;
    }

    private static void collectDescendants(NaruSession session, NaruTask task, List<NaruTask> out) {
        for (long childId : session.findTaskIdsByParent(task.id())) {
            NOptional<NaruTask> child = session.findTask(childId);
            if (child.isPresent()) {
                out.add(child.get());
                collectDescendants(session, child.get(), out);
            }
        }
    }

    private static void readNames(NArrayElement arr, Set<String> into) {
        if (arr == null) {
            return;
        }
        for (NElement e : arr.children()) {
            String n = e.asStringValue().orNull();
            if (!NBlankable.isBlank(n)) {
                into.add(n);
            }
        }
    }

    private static void readNames(NArrayElement arr, Map<String, Boolean> into, Boolean value) {
        if (arr == null) {
            return;
        }
        for (NElement e : arr.children()) {
            String n = e.asStringValue().orNull();
            if (!NBlankable.isBlank(n)) {
                into.put(n, value);
            }
        }
    }

    @Override
    public NElement save(NaruSession session) {
        NArrayElementBuilder tasks = NArrayElementBuilder.of();
        for (Map.Entry<Long, Set<String>> e : selection.entrySet()) {
            NArrayElementBuilder loaded = NArrayElementBuilder.of();
            for (String name : e.getValue()) {
                loaded.add(name);
            }
            tasks.add(NElement.ofObjectBuilder()
                    .set("id", e.getKey())
                    .set("loaded", loaded.build())
                    .build());
        }
        return NElement.ofObjectBuilder()
                .set("schemaVersion", SCHEMA_VERSION)
                .set("selection", tasks.build())
                .build();
    }

    @Override
    public void close() {
        selection.clear();
        manager = null;
    }
}