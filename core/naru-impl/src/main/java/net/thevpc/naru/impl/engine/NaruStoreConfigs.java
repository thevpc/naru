package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.store.NaruSessionScope;
import net.thevpc.naru.api.store.NaruStoreConfig;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives a store's configuration from the project this workspace is pointed at.
 * <p>
 * Shared on purpose. A session and the workspace's {@link
 * net.thevpc.naru.api.agent.NaruSessionStoreManager} must agree on where a store lives and
 * which one it is, or the catalog would list sessions from a store no session ever writes
 * to — exactly the drift that made the desktop show rows it could not then delete. Keeping
 * the one copy here is what makes that impossible.
 */
final class NaruStoreConfigs {

    private NaruStoreConfigs() {
    }

    /**
     * The store config for a project, reading the same project env a session reads.
     *
     * @param projectDir the project the store belongs to
     * @param env        the project env, or null when there is none
     */
    static NaruStoreConfig forProject(NPath projectDir, NaruEnv env) {
        // a misspelled or absent scope key falls back to the default rather than failing:
        // refusing to start over a typo in a config key is a poor trade
        NaruSessionScope defaultScope = NaruSessionScope.parse(
                envString(env, "session.defaultScope"));
        if (defaultScope == null) {
            defaultScope = NaruSessionScope.PRIVATE;
        }
        Map<String, String> jdbcProperties = new LinkedHashMap<>();
        for (String key : List.of("naru.jdbc.url", "naru.jdbc.user", "naru.jdbc.password", "naru.jdbc.driver")) {
            String value = System.getProperty(key);
            if (value != null) {
                jdbcProperties.put(key, value);
            }
        }
        NPath dir = projectDir.normalize();
        return new NaruStoreConfig(dir, dir.resolve(".naru"), defaultScope, jdbcProperties);
    }

    /**
     * The configured store name, or null for the default implementation.
     *
     * @param env the project env, or null when there is none
     */
    static String storeName(NaruEnv env) {
        String fromProperty = System.getProperty("naru.session.store");
        if (fromProperty != null && !fromProperty.trim().isEmpty()) {
            return fromProperty.trim();
        }
        String fromEnv = envString(env, "session.store");
        return fromEnv == null || fromEnv.trim().isEmpty() ? null : fromEnv.trim();
    }

    private static String envString(NaruEnv env, String key) {
        if (env == null) {
            return null;
        }
        NOptional<net.thevpc.nuts.elem.NElement> value = env.get(key);
        return value.flatMap(x -> x.asStringValue()).orNull();
    }
}
