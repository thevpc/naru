package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps providers and protocols written against the session/task signatures working.
 *
 * <p>Extensions are loaded at runtime and may have been compiled before
 * {@link NaruModelContext} existed: such a class implements {@code findModelIds(NaruSession)}
 * but not {@code findModelIds(NaruModelContext)}, which the engine now calls. Each new method
 * therefore defaults to the old one when, and only when, the class actually overrides the old
 * one; a class that implements neither fails with a message naming the method instead of the
 * two defaults calling each other forever.
 *
 * <p>Package-private on purpose: the session or task behind a context is reachable here so the
 * old signatures can be served, and nowhere else.
 */
final class NaruModelLegacy {
    private static final ClassValue<Map<String, Boolean>> OVERRIDES = new ClassValue<>() {
        @Override
        protected Map<String, Boolean> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private NaruModelLegacy() {
    }

    /** Whether {@code type} overrides the interface's own default of {@code name(params)}. */
    static boolean overrides(Class<?> type, Class<?> declaringInterface, String name, Class<?>... params) {
        String key = name + java.util.Arrays.toString(params);
        return OVERRIDES.get(type).computeIfAbsent(key, k -> {
            try {
                Method m = type.getMethod(name, params);
                return m.getDeclaringClass() != declaringInterface;
            } catch (NoSuchMethodException e) {
                return false;
            }
        });
    }

    /**
     * Whether a class between {@code type} and {@code base} (exclusive) declares
     * {@code name(params)}, at any visibility: for protected hooks {@link #overrides} cannot see.
     */
    static boolean overridesDeclared(Class<?> type, Class<?> base, String name, Class<?>... params) {
        String key = "declared:" + base.getName() + "#" + name + java.util.Arrays.toString(params);
        return OVERRIDES.get(type).computeIfAbsent(key, k -> {
            for (Class<?> c = type; c != null && c != base; c = c.getSuperclass()) {
                try {
                    c.getDeclaredMethod(name, params);
                    return true;
                } catch (NoSuchMethodException e) {
                    // keep walking up
                }
            }
            return false;
        });
    }

    static NaruSession session(NaruModelContext context) {
        return context instanceof NaruSessionModelContext c ? c.session : null;
    }

    static NaruTask task(NaruModelContext context) {
        return context instanceof NaruTaskModelContext c ? c.task : null;
    }

    static UnsupportedOperationException missing(Object implementation, String method) {
        return new UnsupportedOperationException(implementation.getClass().getName()
                + " implements neither " + method + "(..., NaruModelContext) nor its deprecated"
                + " session/task form");
    }
}
