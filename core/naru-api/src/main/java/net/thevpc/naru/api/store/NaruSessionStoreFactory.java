package net.thevpc.naru.api.store;

import net.thevpc.nuts.spi.NComponent;
import net.thevpc.nuts.util.NOptional;

/**
 * Makes a {@link NaruSessionStore} for a {@link NaruStoreConfig}.
 *
 * <p>Discovered the same way as every other NARU extension -- a class listed in
 * {@code META-INF/services/net.thevpc.nuts.spi.NComponent} and picked up through
 * {@code NExtensions}. One store per project, so an implementation may keep whatever
 * caches and file handles it likes for the lifetime of the returned store and rely on
 * {@link NaruSessionStore#close} being called.
 *
 * <p>A factory that cannot serve a config returns empty rather than throwing. A project
 * that has an explicitly configured, unavailable store should fail loudly; a project with
 * nothing configured should quietly fall back to the default. Which is which is decided by
 * the caller, not here -- hence an empty return rather than an exception.
 */
public interface NaruSessionStoreFactory extends NComponent {

    /**
     * A short stable name, used to select this implementation from configuration.
     */
    String name();

    /**
     * Opens a store, or returns empty if this implementation does not handle the config.
     */
    NOptional<NaruSessionStore> open(NaruStoreConfig config);

    /**
     * The implementation used when configuration names none. Exactly one factory in the
     * classpath should answer true; the first by {@link #name()} wins, and ties are broken
     * deterministically so that two machines with the same classpath pick the same store.
     */
    default boolean isDefault() {
        return false;
    }
}