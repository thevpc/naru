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
}
