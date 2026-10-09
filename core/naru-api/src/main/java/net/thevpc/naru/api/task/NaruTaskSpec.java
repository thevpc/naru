package net.thevpc.naru.api.task;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.spawn.NaruSpawnContract;
import net.thevpc.naru.api.spawn.NaruSpawnInherit;
import net.thevpc.naru.api.spawn.NaruSpawnStrategy;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NaruTaskSpec {
    private long parentId=-1;
    private String name;
    private NPath workingDirectory;
    private List<String> statements = new ArrayList<>();
    private NaruPromptMode promptMode;
    private final Set<String> toolTags = new LinkedHashSet<>();
    private final Map<String, Object> vars = new LinkedHashMap<>();
    // ── spawn inputs (WP3: strategy, seeds with provenance, named policy, contract) ──
    private NaruSpawnStrategy strategy;
    private int windowTurns;
    private final Set<NaruSpawnInherit> spawnInherit = new LinkedHashSet<>();
    private final Set<String> addTags = new LinkedHashSet<>();
    private final Set<String> revokeTags = new LinkedHashSet<>();
    private final Set<String> excludeTools = new LinkedHashSet<>();
    private final Set<String> addSkills = new LinkedHashSet<>();
    private String policy;
    private String spawnKind;
    private NaruSpawnContract contract;
    private String model;
    private final Map<String, Object> ext = new LinkedHashMap<>();

    public static NaruTaskSpec of() {
        return new NaruTaskSpec();
    }

    public NaruTaskSpec() {

    }

    public long parentId() {
        return parentId;
    }

    public NaruTaskSpec parentId(long parentId) {
        this.parentId = parentId;
        return this;
    }

    public NPath workingDirectory() {
        return workingDirectory;
    }

    public NaruTaskSpec workingDirectory(NPath workingDirectory) {
        this.workingDirectory = workingDirectory;
        return this;
    }

    public List<String> statements() {
        return statements;
    }

    public NaruTaskSpec statements(String... commands) {
        this.statements = commands == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(commands));
        return this;
    }

    public NaruTaskSpec statements(List<String> commands) {
        this.statements = commands == null ? new ArrayList<>() : new ArrayList<>(commands);
        return this;
    }

    public String name() {
        return name;
    }

    public NaruTaskSpec name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Prompt mode to run the task in. When {@code null} the task inherits its
     * parent's mode, falling back to {@code PLANNING} for root tasks.
     * <p>
     * Declaring it here rather than calling {@code NaruTask.promptMode(..)} after
     * {@code newTask(..)} matters: a freshly created task is already visible to
     * scheduler workers, so a mode flipped after the fact leaves a window in
     * which the task runs under the inherited mode.
     */
    public NaruPromptMode promptMode() {
        return promptMode;
    }

    public NaruTaskSpec promptMode(NaruPromptMode promptMode) {
        this.promptMode = promptMode;
        return this;
    }

    /**
     * Tool tags granted to the task. A task can only see a tagged tool if it
     * holds at least one of that tool's tags, so these must be granted
     * explicitly; tags are never inherited from the parent.
     */
    public Set<String> toolTags() {
        return toolTags;
    }

    public NaruTaskSpec toolTags(String... tags) {
        return toolTags(tags == null ? new ArrayList<>() : Arrays.asList(tags));
    }

    public NaruTaskSpec toolTags(List<String> tags) {
        toolTags.clear();
        if (tags != null) {
            for (String tag : tags) {
                if (!NBlankable.isBlank(tag)) {
                    toolTags.add(tag.trim());
                }
            }
        }
        return this;
    }

    /**
     * Replace the initial task variables, replacing anything set earlier.
     * <p>
     * This is how a host passes real inputs in. The alternative -- a leading
     * {@code /set --task name = value} statement -- forces every value through the script
     * parser, which means stringifying anything that is not a literal and quoting anything
     * containing spaces.
     *
     * @param vars the variables, may be null to clear; null keys are ignored
     */
    public NaruTaskSpec vars(Map<String, Object> vars) {
        this.vars.clear();
        if (vars != null) {
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                if (entry.getKey() != null) {
                    this.vars.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return this;
    }

    /**
     * Add or replace a single initial task variable, keeping the others.
     */
    public NaruTaskSpec var(String name, Object value) {
        if (name != null) {
            vars.put(name, value);
        }
        return this;
    }

    /**
     * The initial task variables, unmodifiable. Empty rather than null when none were set.
     */
    public Map<String, Object> vars() {
        return Collections.unmodifiableMap(vars);
    }

    public NaruTaskSpec resolveName() {
        return resolveNameOr(NBlankable.isBlank(name) ? "task" : name);
    }

    public NaruTaskSpec resolveNameOr(String name) {
        if (statements.size() == 1) {
            String a = statements.get(0);
            if(a.startsWith("/call ")){
                a=a.substring(6).trim();
            }else if(a.startsWith("/source ")){
                a=a.substring(8).trim();
            }else if(a.startsWith("/start ")){
                a=a.substring(7).trim();
            }
            String name2 = NPath.of(a).nameParts().baseName();
            if(NBlankable.isBlank(name2)){
                this.name=name;
            }else{
                this.name=name2;
            }
        }else{
            this.name=name;
        }
        return this;
    }

    // ── spawn inputs (WP3) ─────────────────────────────────────────────────

    /**
     * The context strategy for the spawned task, or null for the default
     * ({@link NaruSpawnStrategy#NONE}). Recorded on the spawn event with provenance.
     */
    public NaruSpawnStrategy strategy() {
        return strategy;
    }

    public NaruTaskSpec strategy(NaruSpawnStrategy strategy) {
        this.strategy = strategy;
        return this;
    }

    /** The window size when {@link #strategy()} is {@link NaruSpawnStrategy#WINDOW}. */
    public int windowTurns() {
        return windowTurns;
    }

    public NaruTaskSpec windowTurns(int windowTurns) {
        this.windowTurns = Math.max(0, windowTurns);
        return this;
    }

    /**
     * The kinds of parent state to inherit as a spawn-time snapshot
     * ({@code --inherit=tags}, {@code --inherit=env}). Empty means none.
     */
    public Set<NaruSpawnInherit> inherit() {
        return Collections.unmodifiableSet(spawnInherit);
    }

    public NaruTaskSpec inherit(NaruSpawnInherit... kinds) {
        spawnInherit.clear();
        if (kinds != null) {
            for (NaruSpawnInherit k : kinds) {
                if (k != null) {
                    spawnInherit.add(k);
                }
            }
        }
        return this;
    }

    /** Tags to add on top of whatever is inherited ({@code --add-tags=...}). */
    public Set<String> addTags() {
        return Collections.unmodifiableSet(addTags);
    }

    public NaruTaskSpec addTags(String... tags) {
        return addTags(tags == null ? new ArrayList<>() : Arrays.asList(tags));
    }

    public NaruTaskSpec addTags(List<String> tags) {
        addTags.clear();
        if (tags != null) {
            for (String t : tags) {
                if (!NBlankable.isBlank(t)) {
                    addTags.add(t.trim());
                }
            }
        }
        return this;
    }

    /** Tags to revoke from the resolved set ({@code --revoke-tags=...}). */
    public Set<String> revokeTags() {
        return Collections.unmodifiableSet(revokeTags);
    }

    public NaruTaskSpec revokeTags(String... tags) {
        return revokeTags(tags == null ? new ArrayList<>() : Arrays.asList(tags));
    }

    public NaruTaskSpec revokeTags(List<String> tags) {
        revokeTags.clear();
        if (tags != null) {
            for (String t : tags) {
                if (!NBlankable.isBlank(t)) {
                    revokeTags.add(t.trim());
                }
            }
        }
        return this;
    }

    /** Tool exclusions to add for the spawned task ({@code --exclude-tools=...}). */
    public Set<String> excludeTools() {
        return Collections.unmodifiableSet(excludeTools);
    }

    public NaruTaskSpec excludeTools(String... tools) {
        return excludeTools(tools == null ? new ArrayList<>() : Arrays.asList(tools));
    }

    public NaruTaskSpec excludeTools(List<String> tools) {
        excludeTools.clear();
        if (tools != null) {
            for (String t : tools) {
                if (!NBlankable.isBlank(t)) {
                    excludeTools.add(t.trim());
                }
            }
        }
        return this;
    }

    /** Skill names to add for the spawned task ({@code --add-skills=...}). */
    public Set<String> addSkills() {
        return Collections.unmodifiableSet(addSkills);
    }

    public NaruTaskSpec addSkills(String... skills) {
        return addSkills(skills == null ? new ArrayList<>() : Arrays.asList(skills));
    }

    public NaruTaskSpec addSkills(List<String> skills) {
        addSkills.clear();
        if (skills != null) {
            for (String s : skills) {
                if (!NBlankable.isBlank(s)) {
                    addSkills.add(s.trim());
                }
            }
        }
        return this;
    }

    /** The named spawn policy to apply, or null ({@code --policy=...}). */
    public String policy() {
        return policy;
    }

    public NaruTaskSpec policy(String policy) {
        this.policy = policy;
        return this;
    }

    /**
     * What is being spawned: {@code "routine"}, {@code "agent"}, {@code "model"}, ... Used
     * for spawn-kind defaults and for the provenance of the {@code TaskSpawned} event.
     */
    public String spawnKind() {
        return spawnKind;
    }

    public NaruTaskSpec spawnKind(String spawnKind) {
        this.spawnKind = spawnKind;
        return this;
    }

    /** The target contract to validate after resolution, or null. */
    public NaruSpawnContract contract() {
        return contract;
    }

    public NaruTaskSpec contract(NaruSpawnContract contract) {
        this.contract = contract;
        return this;
    }

    /** Scalar override: model name for the spawned task (inherit-or-override only). */
    public String model() {
        return model;
    }

    public NaruTaskSpec model(String model) {
        this.model = model;
        return this;
    }

    /**
     * Generic extension slot: a place for extensions and directives to stash spawn inputs
     * without the core knowing what they mean. Round-tripped by callers that own the keys;
     * the core ignores it during resolution.
     */
    public Map<String, Object> ext() {
        return Collections.unmodifiableMap(ext);
    }

    public Object ext(String key) {
        return ext.get(key);
    }

    public NaruTaskSpec ext(String key, Object value) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("ext key must not be blank");
        }
        if (value == null) {
            ext.remove(key);
        } else {
            ext.put(key, value);
        }
        return this;
    }
}
