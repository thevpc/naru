package net.thevpc.naru.api.project;

import net.thevpc.naru.api.store.NaruPage;
import net.thevpc.naru.api.store.NaruProjectStoreConfig;
import net.thevpc.naru.api.store.NaruProjectStoreFactory;
import net.thevpc.nuts.artifact.NDefinition;
import net.thevpc.nuts.ext.NServiceLoader;
import net.thevpc.nuts.reflect.NClassLoader;
import net.thevpc.nuts.util.NOptional;

/**
 * The project store: a storage SPI for projects only. It knows nothing about sessions —
 * the only link between the two stores is {@code session.projectId}, and it lives on the
 * session side.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>Validation.</b> Every write re-checks {@link NaruProject}'s field rules; no
 *       operation can persist an invalid project.</li>
 *   <li><b>Uniqueness.</b> {@code (user, name)} is unique, compared case-insensitively
 *       after normalization; {@code user = null} is its own namespace. Violations throw
 *       {@link NaruProjectNameConflictException}. Folders are not unique.</li>
 *   <li><b>Optimistic concurrency.</b> {@link #update} and {@link #delete} carry an
 *       {@code expectedVersion}; a stale token throws
 *       {@link NaruProjectConflictException} and changes nothing. A successful write
 *       increments {@code version} and stamps {@code updatedAt}.</li>
 *   <li><b>Atomic records.</b> A project file is written atomically (temp plus rename in
 *       the same directory): a crash never leaves a half-written project.</li>
 *   <li><b>Resilient listing.</b> A corrupt or unreadable project file is skipped from
 *       {@link #findByQuery} with a logged warning; the listing itself never fails
 *       because of one bad row. {@link #findById} on a corrupt file throws a clear error
 *       naming the file.</li>
 *   <li><b>Keyset paging.</b> Cursors are opaque over {@code (sortKey, id)} and stable
 *       under concurrent inserts: a full traversal neither repeats rows nor skips rows
 *       that existed throughout it. A cursor minted for one sort is rejected when the
 *       query names another.</li>
 * </ul>
 *
 * <p>v1 file-store notes: listing is a directory scan; counts and filtering may scan every
 * header. Single-process access to one store directory is assumed — multi-process locking
 * is out of scope for v1.
 */
public interface NaruProjectStore extends AutoCloseable {

    /**
     * Creates a project: assigns the id, sets {@code version = 1} and both timestamps,
     * validates fields and name uniqueness.
     *
     * @throws NaruProjectNameConflictException when {@code (user, name)} already exists
     */
    NaruProject create(NaruProjectDraft draft);

    NOptional<NaruProject> findById(String id);

    /**
     * Applies a patch under optimistic concurrency. Unset fields are left exactly as
     * stored; name uniqueness is re-checked by the caller of a field-changing patch when
     * {@code name} or {@code user} changes (the store enforces it on every write that can
     * affect the unique pair, excluding the row being updated itself).
     *
     * @throws NaruProjectConflictException    when the stored version is not
     *                                          {@code expectedVersion}
     * @throws NaruProjectNotFoundException    when no project carries this id
     * @throws NaruProjectNameConflictException when the patch would create a duplicate
     *                                          {@code (user, name)}
     */
    NaruProject update(String id, long expectedVersion, NaruProjectPatch patch);

    /**
     * Deletes one project row. Deciding what happens to the sessions that still name this
     * project is a service concern (see {@code NaruProjectDeletePolicy}); the store only
     * removes its own record.
     *
     * @throws NaruProjectConflictException when the stored version is not
     *                                      {@code expectedVersion}
     * @throws NaruProjectNotFoundException when no project carries this id
     */
    void delete(String id, long expectedVersion);

    /** One keyset page of projects. {@code nextCursor} is null when the traversal is exhausted. */
    NaruPage<NaruProject> findByQuery(NaruProjectQuery query);

    /** Cheap existence probe by id. */
    default boolean exists(String id) {
        return findById(id).isPresent();
    }

    @Override
    default void close() {
    }

    /** Convenience for stores that serve exactly one config. */
    default NaruProjectStoreConfig config() {
        throw new UnsupportedOperationException("config is not exposed by " + getClass().getName());
    }

    /**
     * Open a project store for a config, choosing the backend exactly the way the engine does
     * when it builds a workspace: the factory named in the configuration first — a name that
     * is not available fails loudly, because silently writing to a different backend is worse
     * than not starting — and otherwise the default factories sorted by name.
     *
     * <p>This lives on the SPI, not only in the engine, because a host sometimes has to read a
     * folder that is not its own workspace: the desktop's group form looks at the folder the
     * user just picked to see whether a store is already there. The engine's resolver is not
     * public surface, and a host must not reach into {@code naru.impl} for it.
     *
     * @param configuredName the store name from the host's configuration, null for the default
     * @param classLoader    the loader that can see the store extensions
     * @throws IllegalStateException when no factory can serve the config
     */
    static NaruProjectStore open(NaruProjectStoreConfig config, String configuredName,
                                 NClassLoader classLoader) {
        java.util.List<NaruProjectStoreFactory> factories = NServiceLoader
                .of(NaruProjectStoreFactory.class, null, classLoader.asClassLoader())
                .loadAll(null);
        NaruProjectStoreFactory chosen = null;
        if (configuredName != null && !configuredName.isBlank()) {
            for (NaruProjectStoreFactory f : factories) {
                if (configuredName.equals(f.name())) {
                    chosen = f;
                    break;
                }
            }
            if (chosen == null) {
                throw new IllegalStateException("no project store named '" + configuredName
                        + "' is available; found " + names(factories));
            }
        }
        if (chosen == null) {
            java.util.List<NaruProjectStoreFactory> defaults = new java.util.ArrayList<>();
            for (NaruProjectStoreFactory f : factories) {
                if (f.isDefault()) {
                    defaults.add(f);
                }
            }
            defaults.sort(java.util.Comparator.comparing(NaruProjectStoreFactory::name));
            chosen = defaults.isEmpty() ? null : defaults.get(0);
        }
        if (chosen == null) {
            throw new IllegalStateException("no project store is available; found " + names(factories));
        }
        NOptional<NaruProjectStore> store = chosen.open(config);
        if (!store.isPresent()) {
            throw new IllegalStateException("project store '" + chosen.name()
                    + "' cannot serve " + config);
        }
        return store.get();
    }

    /** Open the default store backend with the class loader that loaded this interface. */
    static NaruProjectStore open(NaruProjectStoreConfig config) {
        return open(config, null, NClassLoader.of("naru-project-store",
                NaruProjectStore.class.getClassLoader(), new NDefinition[0]));
    }

    private static String names(java.util.List<NaruProjectStoreFactory> factories) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (NaruProjectStoreFactory f : factories) {
            out.add(f.name());
        }
        return out.toString();
    }
}
