package net.thevpc.naru.api.store;

import net.thevpc.nuts.io.NPath;

/**
 * What a store needs to know before it can open itself.
 *
 * <p>Deliberately minimal and deliberately typed: a store is handed a root it may write to
 * and nothing else. It is not handed a session, a scheduler or an engine, so it cannot
 * reach back into the thing that is using it and turn persistence into a cycle.
 */
public class NaruStoreConfig {

    private final NPath projectDir;
    private final NPath storeDir;
    private final NaruSessionScope defaultScope;

    /**
     * @param projectDir    the project this store belongs to; used only for messages and
     *                      for stores that can lay their files out relative to it
     * @param storeDir      the directory this store owns. A file store puts both session
     *                      scopes inside it, under {@code sessions/} and
     *                      {@code local/sessions/}.
     * @param defaultScope  where a session lands when nothing says otherwise
     */
    public NaruStoreConfig(NPath projectDir, NPath storeDir, NaruSessionScope defaultScope) {
        this.projectDir = projectDir;
        this.storeDir = storeDir;
        this.defaultScope = defaultScope == null ? NaruSessionScope.PRIVATE : defaultScope;
    }

    public static NaruStoreConfig ofProject(NPath projectDir) {
        return new NaruStoreConfig(projectDir, projectDir.resolve(".naru"), NaruSessionScope.PRIVATE);
    }

    public NPath projectDir() {
        return projectDir;
    }

    public NPath storeDir() {
        return storeDir;
    }

    public NaruSessionScope defaultScope() {
        return defaultScope;
    }

    @Override
    public String toString() {
        return "NaruStoreConfig{projectDir=" + projectDir + ", storeDir=" + storeDir
                + ", defaultScope=" + defaultScope + '}';
    }
}