package net.thevpc.naru.api.spawn;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.nuts.io.NPath;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named spawn policy, defined by the {@code /spawn-policy} directive (typically in an
 * init script) and referenced by {@code /start --policy=&lt;name&gt;} or by a tool on the
 * model path.
 * <p>
 * A policy is a bundle of seed values applied <em>after</em> the extension / spawn-kind
 * defaults and <em>before</em> the call-site flags, and it is recorded with provenance
 * {@link NaruSpawnSource#POLICY} on the {@code TaskSpawned} event. It defines no new file
 * format: policies live in the session, re-declared by the init script that runs on every
 * session start.
 * <p>
 * The scalar configs it pins (model, working dir, prompt mode) are inherit-or-override
 * only: a policy may set them for spawned tasks, but there is no "revoke" of a scalar —
 * revoke applies to set-valued seeds (tags, exclusions). A policy may grant tags
 * ({@code addTags}); it is the <em>contract</em> that may not.
 */
public final class NaruSpawnPolicy {

    /**
     * The session-env key a model-path caller reads for the policy name to apply when it
     * spawns (the "configured named policy" of the model-initiated spawn).
     */
    public static final String ENV_CONFIG_POLICY = "spawn.policy";

    private final String name;
    private final Set<NaruSpawnInherit> inherit = new LinkedHashSet<>();
    private final Set<String> addTags = new LinkedHashSet<>();
    private final Set<String> revokeTags = new LinkedHashSet<>();
    private final Set<String> addSkills = new LinkedHashSet<>();
    private final Set<String> excludeTools = new LinkedHashSet<>();
    private NaruSpawnStrategy strategy;
    private int windowTurns;
    private String model;
    private NPath workingDir;
    private NaruPromptMode promptMode;

    public NaruSpawnPolicy(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Set<NaruSpawnInherit> inherit() {
        return Collections.unmodifiableSet(inherit);
    }

    public NaruSpawnPolicy inherit(NaruSpawnInherit... kinds) {
        inherit.clear();
        if (kinds != null) {
            for (NaruSpawnInherit k : kinds) {
                if (k != null) {
                    inherit.add(k);
                }
            }
        }
        return this;
    }

    public Set<String> addTags() {
        return Collections.unmodifiableSet(addTags);
    }

    public NaruSpawnPolicy addTags(String... tags) {
        return addTags(tags == null ? Collections.emptyList() : java.util.Arrays.asList(tags));
    }

    public NaruSpawnPolicy addTags(java.util.List<String> tags) {
        addTags.clear();
        if (tags != null) {
            for (String t : tags) {
                if (t != null && !t.isBlank()) {
                    addTags.add(t.trim());
                }
            }
        }
        return this;
    }

    public Set<String> revokeTags() {
        return Collections.unmodifiableSet(revokeTags);
    }

    public NaruSpawnPolicy revokeTags(String... tags) {
        return revokeTags(tags == null ? Collections.emptyList() : java.util.Arrays.asList(tags));
    }

    public NaruSpawnPolicy revokeTags(java.util.List<String> tags) {
        revokeTags.clear();
        if (tags != null) {
            for (String t : tags) {
                if (t != null && !t.isBlank()) {
                    revokeTags.add(t.trim());
                }
            }
        }
        return this;
    }

    public Set<String> addSkills() {
        return Collections.unmodifiableSet(addSkills);
    }

    public NaruSpawnPolicy addSkills(String... skills) {
        return addSkills(skills == null ? Collections.emptyList() : java.util.Arrays.asList(skills));
    }

    public NaruSpawnPolicy addSkills(java.util.List<String> skills) {
        addSkills.clear();
        if (skills != null) {
            for (String s : skills) {
                if (s != null && !s.isBlank()) {
                    addSkills.add(s.trim());
                }
            }
        }
        return this;
    }

    public Set<String> excludeTools() {
        return Collections.unmodifiableSet(excludeTools);
    }

    public NaruSpawnPolicy excludeTools(String... tools) {
        return excludeTools(tools == null ? Collections.emptyList() : java.util.Arrays.asList(tools));
    }

    public NaruSpawnPolicy excludeTools(java.util.List<String> tools) {
        excludeTools.clear();
        if (tools != null) {
            for (String t : tools) {
                if (t != null && !t.isBlank()) {
                    excludeTools.add(t.trim());
                }
            }
        }
        return this;
    }

    public NaruSpawnStrategy strategy() {
        return strategy;
    }

    public NaruSpawnPolicy strategy(NaruSpawnStrategy strategy) {
        this.strategy = strategy;
        return this;
    }

    public int windowTurns() {
        return windowTurns;
    }

    public NaruSpawnPolicy windowTurns(int windowTurns) {
        this.windowTurns = Math.max(0, windowTurns);
        return this;
    }

    public String model() {
        return model;
    }

    public NaruSpawnPolicy model(String model) {
        this.model = model;
        return this;
    }

    public NPath workingDir() {
        return workingDir;
    }

    public NaruSpawnPolicy workingDir(NPath workingDir) {
        this.workingDir = workingDir;
        return this;
    }

    public NaruPromptMode promptMode() {
        return promptMode;
    }

    public NaruSpawnPolicy promptMode(NaruPromptMode promptMode) {
        this.promptMode = promptMode;
        return this;
    }

    @Override
    public String toString() {
        return "spawn-policy " + name;
    }
}