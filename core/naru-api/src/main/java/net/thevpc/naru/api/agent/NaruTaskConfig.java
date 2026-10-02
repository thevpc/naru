package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a configuration value for a task, applying NARU's precedence chain.
 *
 * <p>Exists because the chain did not exist. Settings were read ad hoc -- {@code maxSteps}
 * from session env, {@code model.noStream} from session env with a project-env fallback,
 * {@code model} from project env only -- each with its own slightly different rules and none
 * of them overridable per task. An extension with settings needs one rule it can document,
 * not three it has to rediscover.
 *
 * <p>The chain, first hit wins:
 *
 * <ol>
 *   <li><b>task env</b> -- {@code /set} in this task, or inherited from its parent. The
 *       narrowest scope, and the one a script should use.</li>
 *   <li><b>session env</b> -- {@code /set} at session level; shared by sibling tasks.</li>
 *   <li><b>project env</b> -- {@code .naru/local/config/env.tson} then
 *       {@code .naru/config/env.tson}, private winning. Checked into the repository, so it
 *       is how a project ships its defaults to everyone who opens it.</li>
 *   <li><b>JVM system property</b> -- {@code -Dnaru.some.key=...}. Below the project file on
 *       purpose: a checked-in value the user can see should beat one only they can set.
 *       Ahead of the built-in default because it is still an explicit instruction.</li>
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
        if (task == null || key == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("no task to resolve '%s' against", key));
        }
        // 1. task env, inheritance included: a child task is configured by its parent
        NOptional<Object> taskEnv = task.getTaskEnv(key, true);
        if (taskEnv != null && taskEnv.isPresent()) {
            return taskEnv;
        }
        NaruSession session = task.session();
        // 2. session env, shared by sibling tasks
        if (session != null) {
            NOptional<Object> sessionEnv = session.getSessionEnv(key);
            if (sessionEnv != null && sessionEnv.isPresent()) {
                return sessionEnv;
            }
            // 3. project env: getProjectEnv already checks the private file first
            NOptional<?> projectEnv = session.getProjectEnv(key);
            if (projectEnv != null && projectEnv.isPresent()) {
                return projectEnv.map(x -> (Object) x);
            }
        }
        // 4. JVM system property, the operator's escape hatch
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return NOptional.of(property);
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
            net.thevpc.nuts.elem.NElement e = (net.thevpc.nuts.elem.NElement) value;
            if (e.isNull()) {
                return null;
            }
            String asString = e.asStringValue().orNull();
            if (asString != null) {
                return asString;
            }
            return net.thevpc.nuts.elem.NElementWriter.ofTson().compact(true).formatPlain(e);
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