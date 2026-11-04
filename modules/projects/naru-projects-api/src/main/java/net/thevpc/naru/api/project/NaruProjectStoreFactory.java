package net.thevpc.naru.api.project;

import net.thevpc.naru.api.store.NaruSessionStoreFactory;

import net.thevpc.naru.api.project.NaruProjectStore;
import net.thevpc.nuts.spi.NComponent;
import net.thevpc.nuts.util.NOptional;

/**
 * Discovery seam for project store backends, exactly mirroring
 * {@link NaruSessionStoreFactory}: implementations are listed in
 * {@code META-INF/services/net.thevpc.nuts.spi.NComponent}, and the engine picks by
 * configured name first, then the default.
 */
public interface NaruProjectStoreFactory extends NComponent {

    String name();

    /** Opens this backend for the given config, or empty when it cannot serve it. */
    NOptional<NaruProjectStore> open(NaruProjectStoreConfig config);

    /** The backend used when no name is configured. */
    default boolean isDefault() {
        return false;
    }
}
