package net.thevpc.naru.api.store;

import net.thevpc.nuts.spi.NComponent;
import net.thevpc.nuts.util.NOptional;

/** Shared backend provider discovered through the NARU extension class loader. */
public interface NaruDataStoreProvider extends NComponent {
    String name();

    NOptional<NaruDataStore> open(NaruDataStoreConfig config);
}
