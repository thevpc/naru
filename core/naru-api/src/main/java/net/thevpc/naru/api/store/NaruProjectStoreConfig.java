package net.thevpc.naru.api.store;

import net.thevpc.nuts.io.NPath;

import java.util.Map;

/**
 * What a project store needs to know before it can open itself: where project files live.
 *
 * <p>Mirrors {@link NaruStoreConfig} deliberately: the session store and the project store
 * are separate SPIs with separate layouts, and the only link between them is
 * {@code session.projectId}. The default file store keeps one {@code <id>.tson} per project
 * under {@code root}; nothing here names a session.
 *
 * <p>Roots default to {@code .naru/local/projects} — private by design: a project row
 * carries absolute machine-specific paths and must not be committed alongside public
 * sessions.
 */
public final class NaruProjectStoreConfig {

    private final NPath homeDir;
    private final NPath root;
    private final Map<String, String> properties;

    public NaruProjectStoreConfig(NPath homeDir, NPath root) {
        this(homeDir, root, Map.of());
    }

    public NaruProjectStoreConfig(NPath homeDir, NPath root, Map<String, String> properties) {
        this.homeDir = homeDir;
        this.root = root == null
                ? (homeDir == null ? null : homeDir.resolve(".naru").resolve("local").resolve("projects"))
                : root;
        this.properties = Map.copyOf(properties == null ? Map.of() : properties);
    }

    /** The project store root: the directory holding {@code <id>.tson} files. */
    public NPath root() {
        return root;
    }

    /** The directory the store was derived from (the workspace), for messages only. */
    public NPath homeDir() {
        return homeDir;
    }

    public Map<String, String> properties() {
        return properties;
    }

    public String property(String name) {
        return properties.get(name);
    }

    @Override
    public String toString() {
        return "NaruProjectStoreConfig{root=" + root + '}';
    }
}
