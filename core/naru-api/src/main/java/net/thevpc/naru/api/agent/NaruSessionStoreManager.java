package net.thevpc.naru.api.agent;

import java.util.List;

/**
 * The on-disk catalog of saved sessions for one project.
 * <p>
 * This is a <i>store</i>, not a registry of live sessions. Every entry is a directory that
 * already exists on disk under {@code .naru/sessions/}; listing it says nothing about what
 * is running right now. For the live set, ask {@link NaruAgent#sessions()}.
 * <p>
 * The two are kept apart on purpose. A server hosting many concurrent sessions needs a
 * live view that is cheap to read and changes as sessions start and stop; the saved
 * catalog is disk state that outlives the process. Folding them together would force one
 * of the two to lie.
 */
public interface NaruSessionStoreManager {
    List<NaruResourceInfo> list();

    int purge();

    String findByUuidOrName(String uuidOrName);

    boolean delete(String uuidOrName);

    /**
     * Renames a session that is saved in the catalog.
     *
     * <p>This edits the catalog entry in place; it does not touch a running session. Callers
     * that hold a live {@link NaruSession} should rename through that object instead, so the
     * in-memory name and the stored name cannot drift apart.
     *
     * @return true when a saved session with that uuid was found and updated
     */
    default boolean rename(String uuid, String name) {
        return false;
    }

    /**
     * Moves a saved session between the private and the public catalog.
     *
     * <p>Visibility is the location, so this is a move of the whole session folder. As with
     * {@link #rename(String, String)}, a live session is best changed through its own
     * {@link NaruSession#setVisibility(NaruVisibility)} and {@link NaruSession#save()}.
     *
     * @return true when a saved session with that uuid was found and moved
     */
    default boolean setVisibility(String uuid, NaruVisibility visibility) {
        return false;
    }
}
