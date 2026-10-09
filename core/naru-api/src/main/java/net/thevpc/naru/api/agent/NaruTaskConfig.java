package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a configuration value for a task, applying NARU's precedence chain.
 *
 * <p>Exists because the chain did not exist. Settings were read ad hoc -- {@code maxSteps}
 * from session env, {@code model.noStream} from session env with a config-env fallback,
 * {@code model} from the config env only -- each with its own slightly different rules and
 * none of them overridable per task. An extension with settings needs one rule it can
 * document, not three it has to rediscover.
 *
 * <p>Two independent axes, deliberately not merged into one list of "scopes":
 *
 * <ul>
 *   <li><b>which store</b> -- task env, session env, config files, JVM property;</li>
 *   <li><b>who can see it</b> -- private or public, which for the config store means
 *       which of the two files holds it.</li>
 * </ul>
 *
 * <p>Only the config store has a visibility. Folding the two together produces names
 * like "project (private)" that read as a scope while being a store plus a visibility,
 * and hides the question a person actually has when an edit seems to do nothing: which
 * file did I just write, and which one is shadowing it.
 *
 * <p>The chain, first hit wins:
 *
 * <ol>
 *   <li><b>task env</b> -- written by {@code /set} in this task, or inherited from its
 *       parent. The narrowest store, and the one a script should use.</li>
 *   <li><b>session env</b> -- written by {@code /set} at session level; shared by
 *       sibling tasks, gone when the session ends.</li>
 *   <li><b>agent env</b> -- written by {@code /set --agent}; shared by every session of
 *       this process and never persisted, so it overrides a saved setting for one run
 *       without touching a file.</li>
 *   <li><b>config</b> -- {@code .naru/local/config/env.tson} then
 *       {@code .naru/config/env.tson}, private winning. On disk, so it outlives the
 *       session and is how a project ships its defaults to everyone who opens it.</li>
 *   <li><b>JVM system property</b> -- {@code -Dnaru.some.key=...}. Below the config
 *       files on purpose: a checked-in value the user can see should beat one only they
 *       can set. Ahead of the built-in default because it is still an explicit instruction.</li>
 *   <li><b>the caller's default</b> -- always supplied, so there is no "unset" state to
 *       branch on.</li>
 * </ol>
 *
 * <p>Child tasks inherit their parent's env at {@code start}, so a setting resolved this way
 * needs no propagation code of its own.
 *
 * <p>There is no per-user configuration file. Nothing in the project has one, so adding one
 * here would be a second source of truth that nothing else reads; the system property
 * occupies that slot and is described as what it is.
 */
public final class NaruTaskConfig {

    private NaruTaskConfig() {
    }

    /**
     * The raw value for a key, or empty when nothing in the chain defines it.
     *
     * <p>Returns the stored object rather than a parsed one, because env values arrive as
     * {@code NElement} from the project file and as plain Java objects from {@code /set},
     * and the right coercion depends on what the caller wants.
     */
    public static NOptional<Object> find(NaruTask task, String key) {
        return resolve(task, key).map(Resolved::value);
    }

    public static final String SCOPE_TASK = "task";
    public static final String SCOPE_SESSION = "session";
    public static final String SCOPE_AGENT = "agent";
    public static final String SCOPE_CONFIG = "config";
    public static final String SCOPE_SYSTEM_PROPERTY = "system property";

    /**
     * A value together with the store that supplied it and, for the config store,
     * the visibility it was found under.
     *
     * <p>Exists because a first-hit-wins chain over several sources is exactly the
     * situation where "the setting says true and nothing happened" happens: the value
     * was found, but three stores away from the one being read. Anyone displaying a
     * setting needs the store as much as the value, and deriving it outside this
     * class would mean a second copy of the chain that drifts.
     *
     * <p>{@link #visibility()} is null for every store except config. The two axes are
     * separate on purpose: a config value is <em>both</em> "in the config store"
     * <em>and</em> private or public, and reporting only one of them leaves the reader
     * unable to tell which file to edit.
     */
    public static final class Resolved {
        private final Object value;
        private final String scope;
        private final NaruVisibility visibility;

        private Resolved(Object value, String scope, NaruVisibility visibility) {
            this.value = value;
            this.scope = scope;
            this.visibility = visibility;
        }

        /** The raw value, exactly as the winning store held it. */
        public Object value() {
            return value;
        }

        /** One of {@link #SCOPE_TASK}, {@link #SCOPE_SESSION}, {@link #SCOPE_CONFIG}, {@link #SCOPE_SYSTEM_PROPERTY}. */
        public String scope() {
            return scope;
        }

        /** The config file it came from, or null when the store has no visibility. */
        public NaruVisibility visibility() {
            return visibility;
        }
    }

    /**
     * The value for a key along with the store that answered, or empty when nothing
     * in the chain defines it.
     *
     * <p>This is the chain; {@link #find} is the value-only view of it. Kept as one
     * implementation so a display of "where did this come from" cannot disagree
     * with the lookup that produced the value.
     */
    public static NOptional<Resolved> resolve(NaruTask task, String key) {
        if (task == null || key == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("no task to resolve '%s' against", key));
        }
        // 1. task env, inheritance included: a child task is configured by its parent
        NOptional<Object> taskEnv = task.getTaskEnv(key, true);
        if (taskEnv != null && taskEnv.isPresent()) {
            return NOptional.of(new Resolved(taskEnv.get(), SCOPE_TASK, null));
        }
        NaruSession session = task.session();
        // 2. session env, shared by sibling tasks
        if (session != null) {
            NOptional<Object> sessionEnv = session.getSessionEnv(key);
            if (sessionEnv != null && sessionEnv.isPresent()) {
                return NOptional.of(new Resolved(sessionEnv.get(), SCOPE_SESSION, null));
            }
            // 3. agent env, shared by every session of this process but not persisted
            NaruAgent agent = session.agent();
            if (agent != null) {
                NOptional<NElement> agentValue = agent.agentEnv().get(key);
                if (agentValue != null && agentValue.isPresent()) {
                    return NOptional.of(new Resolved(agentValue.get(), SCOPE_AGENT, null));
                }
            }
            // 4. config, asked per visibility so the answer can name the file. Private
            // first, matching what getProjectEnv(key) resolves to on its own.
            NOptional<NElement> privateValue = session.getProjectEnv(key, NaruVisibility.PRIVATE);
            if (privateValue != null && privateValue.isPresent()) {
                return NOptional.of(new Resolved(privateValue.get(), SCOPE_CONFIG, NaruVisibility.PRIVATE));
            }
            NOptional<NElement> publicValue = session.getProjectEnv(key, NaruVisibility.PUBLIC);
            if (publicValue != null && publicValue.isPresent()) {
                return NOptional.of(new Resolved(publicValue.get(), SCOPE_CONFIG, NaruVisibility.PUBLIC));
            }
        }
        // 5. JVM system property, the operator's escape hatch
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return NOptional.of(new Resolved(property, SCOPE_SYSTEM_PROPERTY, null));
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("'%s' is not set in any scope", key));
    }

    /** The value as a string, or {@code defaultValue} when unset. */
    public static String getString(NaruTask task, String key, String defaultValue) {
        return find(task, key).map(x -> toText(x)).orElse(defaultValue);
    }

    public static boolean getBoolean(NaruTask task, String key, boolean defaultValue) {
        return find(task, key)
                .map(x -> NLiteral.of(toText(x)).asBoolean().orElse(defaultValue))
                .orElse(defaultValue);
    }

    public static long getLong(NaruTask task, String key, long defaultValue) {
        return find(task, key)
                .map(x -> NLiteral.of(toText(x)).asLong().orElse(defaultValue))
                .orElse(defaultValue);
    }

    public static double getDouble(NaruTask task, String key, double defaultValue) {
        return find(task, key)
                .map(x -> NLiteral.of(toText(x)).asDouble().orElse(defaultValue))
                .orElse(defaultValue);
    }

    /**
     * The value as an ordered list, or {@code defaultValue} when unset.
     *
     * <p>Accepts a real array and a comma-separated string, because a list written into a
     * TSON env file arrives as the former and one typed at the prompt as the latter, and a
     * configuration key should not care which door it came through.
     */
    public static List<String> getStringList(NaruTask task, String key, List<String> defaultValue) {
        NOptional<Object> found = find(task, key);
        if (!found.isPresent()) {
            return defaultValue;
        }
        List<String> out = new ArrayList<>();
        Object value = found.get();
        if (value instanceof Iterable) {
            for (Object o : (Iterable<?>) value) {
                if (o != null && !toText(o).isBlank()) {
                    out.add(toText(o).trim());
                }
            }
            return out;
        }
        for (String part : toText(value).split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out.isEmpty() ? defaultValue : out;
    }

    /**
     * Renders any env value as text.
     *
     * <p>An {@code NElement} is stringified rather than unwrapped by type, because a list
     * and a plain string are both stored as elements and the caller usually wants the
     * literal the user wrote.
     */
    public static String toText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof net.thevpc.nuts.elem.NElement) {
            NElement e = (NElement) value;
            if (e.isNull()) {
                return null;
            }
            String asString = e.asStringValue().orNull();
            if (asString != null) {
                return asString;
            }
            return NElementWriter.ofTson().compact(true).formatPlain(e);
        }
        if (value instanceof Iterable) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (Iterable<?>) value) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(toText(o));
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    /**
     * The {@link NaruSource}s a task's context currently includes, as a resolved set.
     *
     * <p>A convenience for the common {@code context(NaruSource.values())} call, kept here
     * so a caller does not have to pass the whole enum to ask a simple question.
     */
    public static NaruSource[] allSources() {
        return NaruSource.values();
    }
}