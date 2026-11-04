package net.thevpc.naru.modules.projects;

import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.project.NaruProjectStoreConfig;
import net.thevpc.nuts.io.NPath;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the project store's configuration from the workspace directory, the same way
 * NaruStoreConfigs (the session store's equivalent, in the kernel) derives the session store's. Shared on purpose: the service
 * and any future caller must agree on one root, or the tree would list projects no
 * session move can see.
 *
 * <p>The root is {@code <workspace>/.naru/local/projects} — under {@code local/} because
 * project rows carry absolute machine-specific paths and must not be committed alongside
 * public sessions. The name of the backend is read from the system property
 * {@code naru.project.store}, then the env key {@code project.store}; absent means the
 * default factory.
 */
public final class NaruProjectStoreConfigs {

    private NaruProjectStoreConfigs() {
    }

    public static NaruProjectStoreConfig forWorkspace(NPath workspaceDir, NaruEnv env) {
        NPath dir = workspaceDir.normalize();
        Map<String, String> properties = new LinkedHashMap<>();
        for (String key : List.of("naru.jdbc.url", "naru.jdbc.user", "naru.jdbc.password", "naru.jdbc.driver")) {
            String value = System.getProperty(key);
            if (value != null) {
                properties.put(key, value);
            }
        }
        return new NaruProjectStoreConfig(dir,
                dir.resolve(".naru").resolve("local").resolve("projects"), properties);
    }

    public static String storeName(NaruEnv env) {
        String fromProperty = System.getProperty("naru.project.store");
        if (fromProperty != null && !fromProperty.trim().isEmpty()) {
            return fromProperty.trim();
        }
        String fromEnv = envString(env, "project.store");
        return fromEnv == null || fromEnv.trim().isEmpty() ? null : fromEnv.trim();
    }

    private static String envString(NaruEnv env, String key) {
        if (env == null) {
            return null;
        }
        net.thevpc.nuts.util.NOptional<net.thevpc.nuts.elem.NElement> value = env.get(key);
        return value.flatMap(x -> x.asStringValue()).orNull();
    }
}
