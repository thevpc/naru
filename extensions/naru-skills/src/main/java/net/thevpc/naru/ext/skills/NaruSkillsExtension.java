package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.api.spawn.NaruSpawnContext;
import net.thevpc.naru.api.spawn.NaruSpawnResolution;
import net.thevpc.naru.api.spawn.NaruSpawnSeed;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.agent.NaruLogMode;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Makes the skills feature available to the session, without the core knowing that skills
 * exist.
 * <p>
 * The extension owns everything the core used to: which skills exist, which are active for
 * which task, how their text is worded into the prompt, and the persisted selection.
 * {@code naru-api} and {@code naru-impl} no longer mention skills at all — removing this
 * jar from the classpath removes the feature, along with {@code /skill} and
 * {@code /context skills}.
 *
 * <h2>How selection resolves</h2>
 * A task inherits its parent's active skills, resolved <em>at read time</em> by walking
 * {@link NaruTask#parentId()}. Each task may add to or mask the set it inherited:
 * loading a skill on a child shadows the parent's choice for that name, and unloading on a
 * child masks a skill the parent still has. That makes {@code /skill unload} work on a
 * child without the parent losing it, and makes a skill loaded on a parent apply to work
 * already dispatched underneath it.
 * <p>
 * Earlier the core snapshotted the parent's set onto the child at spawn time. Resolving at
 * read time replaces that copy with a lookup, which is what lets this state live in an
 * extension at all: the core no longer has to know that a task should be handed a copy.
 */
public class NaruSkillsExtension implements NaruSessionExtension {

    public static final String NAME = "skills";

    private static final int SCHEMA_VERSION = 1;

    /**
     * Per-task selection: task id to (skill name to loaded). A {@code false} marks a skill
     * the task explicitly unloaded, which masks whatever its ancestors had selected.
     */
    private final Map<Long, Map<String, Boolean>> selection = new TreeMap<>();

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
     * Applies the resolved spawn skills to the freshly spawned child and warns when a
     * skill's required tags are inconsistent with what the child was granted.
     * <p>
     * Loading here (rather than at read time) is what the resolved {@code --add-skills} and
     * contract skills mean: the child's own selection gains the spawn grants, and the
     * warning surfaces the mismatch the spawn plan itself cannot see (the core is generic
     * and never parses a skill requirement).
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
        Set<String> granted = new TreeSet<>(task.findToolTagNames());
        for (NaruSpawnSeed<String> seed : resolution.skills()) {
            NaruSkill skill = skills().findSkill(seed.value());
            if (skill == null || skill.isEmpty()) {
                continue;
            }
            load(task, seed.value());
            if (skill.getRequires() == null) {
                continue;
            }
            List<String> violations = skill.getRequires().violations(granted);
            if (!violations.isEmpty()) {
                String detail = violations.stream()
                        .map(v -> v.startsWith("+") ? "lacks " + v.substring(1) : "holds " + v.substring(1))
                        .collect(Collectors.joining(", "));
                task.log(NaruLogMode.SCRIPT, NMsg.ofC(
                        "⚠ skill '%s' requires %s, but the spawned task's tags are inconsistent: %s "
                                + "(grant the missing tags with --add-tags=..., or revoke the conflicting ones with --revoke-tags=...)",
                        skill.getName(), skill.getRequires(), detail));
            }
        }
    }

    @Override
    public boolean isRelevant(NaruTask task) {
        return !selection.isEmpty();
    }

    @Override
    public List<NaruMessage> contribute(NaruTask task) {
        List<NaruMessage> out = new ArrayList<>();
        for (String skillName : activeNames(task)) {
            NaruSkill s = skills().findSkill(skillName);
            if (s == null || s.isEmpty()) {
                // selected but deleted or truncated since it was loaded
                continue;
            }
            out.add(NaruMessage.user(
                    "## ACTIVE SKILL DIRECTIVE: " + s.getName().toUpperCase() + "\n"
                            + String.join("\n", s.getLines())
            ).setSourceName(s.getSourceName()));
        }
        return out;
    }

    // ── selection ───────────────────────────────────────────────────────────

    /**
     * The skill names active for a task, nearest ancestor winning.
     */
    public Set<String> activeNames(NaruTask task) {
        return new TreeSet<>(resolve(task).keySet());
    }

    /**
     * Activates a skill for a task and its descendants.
     *
     * @return true when the skill was not already active for this task.
     */
    public boolean load(NaruTask task, String name) {
        String canonical = canonical(name);
        if (canonical == null || skills().findSkill(canonical) == null) {
            return false;
        }
        Map<String, Boolean> own = selection.computeIfAbsent(task.id(), x -> new TreeMap<>());
        if (Boolean.TRUE.equals(own.get(canonical))) {
            return false;
        }
        own.put(canonical, Boolean.TRUE);
        return true;
    }

    /**
     * Deactivates a skill for a task and its descendants, masking any copy the ancestors
     * still have selected. Ancestors keep their own selection.
     *
     * @return true when the skill was active for this task.
     */
    public boolean unload(NaruTask task, String name) {
        String canonical = canonical(name);
        if (canonical == null) {
            return false;
        }
        // ask before masking: once the mask is in place resolve() already drops the name,
        // so testing afterwards could never tell "was inherited" from "was not active"
        boolean wasActive = resolve(task).containsKey(canonical);
        selection.computeIfAbsent(task.id(), x -> new TreeMap<>()).put(canonical, Boolean.FALSE);
        return wasActive;
    }

    /**
     * Whether a skill exists at all, regardless of selection. Used by the directives to tell
     * "no such skill" apart from "not loaded".
     */
    public boolean exists(String name) {
        String canonical = canonical(name);
        return canonical != null && skills().findSkill(canonical) != null;
    }

    private static String canonical(String name) {
        if (NBlankable.isBlank(name)) {
            return null;
        }
        String c = NNameFormat.LOWER_KEBAB_CASE.format(name.trim());
        return c.isEmpty() ? null : c;
    }

    /**
     * Merges a task's own selection onto its ancestors'.
     */
    private Map<String, Boolean> resolve(NaruTask task) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        NaruTask cursor = task;
        int guard = 0;
        // walk to the root, then apply the nearest ancestor first so nearer overrides win
        List<NaruTask> chain = new ArrayList<>();
        while (cursor != null && guard++ < 256) {
            chain.add(cursor);
            long parentId = cursor.parentId();
            if (parentId < 0) {
                break;
            }
            NOptional<NaruTask> parent = task.session().findTask(parentId);
            if (parent.isEmpty()) {
                break;
            }
            cursor = parent.orElse(null);
        }
        Collections.reverse(chain);
        for (NaruTask t : chain) {
            Map<String, Boolean> own = selection.get(t.id());
            if (own == null) {
                continue;
            }
            for (Map.Entry<String, Boolean> e : own.entrySet()) {
                if (e.getValue()) {
                    result.put(e.getKey(), Boolean.TRUE);
                } else {
                    result.remove(e.getKey());
                }
            }
        }
        return result;
    }

    // ── persistence ───────────────────────────────────────────────────────────

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
            NArrayElement tasks = root.getArray("selection").orNull();
            if (tasks == null) {
                return;
            }
            for (NElement t : tasks.children()) {
                NObjectElement to = t.asObject().orNull();
                if (to == null) {
                    continue;
                }
                Long taskId = to.getLongValue("id").orNull();
                if (taskId == null || taskId < 0) {
                    continue;
                }
                Map<String, Boolean> own = new TreeMap<>();
                readNames(to.getArray("loaded").orNull(), own, Boolean.TRUE);
                readNames(to.getArray("masked").orNull(), own, Boolean.FALSE);
                if (!own.isEmpty()) {
                    selection.put(taskId, own);
                }
            }
        } catch (Exception ex) {
            // a selection that will not parse is worth complaining about, but not worth
            // failing the session load over: the user can re-select, and losing the whole
            // conversation to a malformed selection is not a trade anyone wants
            selection.clear();
            throw new NIllegalArgumentException(
                    NMsg.ofC("failed to load skill selection: %s", ex.getMessage(), ex));
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
        for (Map.Entry<Long, Map<String, Boolean>> e : selection.entrySet()) {
            NArrayElementBuilder loaded = NArrayElementBuilder.of();
            NArrayElementBuilder masked = NArrayElementBuilder.of();
            for (Map.Entry<String, Boolean> s : e.getValue().entrySet()) {
                if (Boolean.TRUE.equals(s.getValue())) {
                    loaded.add(s.getKey());
                } else {
                    masked.add(s.getKey());
                }
            }
            tasks.add(NElement.ofObjectBuilder()
                    .set("id", e.getKey())
                    .set("loaded", loaded.build())
                    .set("masked", masked.build())
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
