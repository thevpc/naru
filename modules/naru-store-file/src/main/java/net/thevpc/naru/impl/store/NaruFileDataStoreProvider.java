package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.store.NaruDataStore;
import net.thevpc.naru.api.store.NaruDataStoreConfig;
import net.thevpc.naru.api.store.NaruDataStoreProvider;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

/** Shared file backend for feature-owned logical stores. */
public final class NaruFileDataStoreProvider implements NaruDataStoreProvider {
    @Override public String name() { return "file"; }

    @Override
    public NOptional<NaruDataStore> open(NaruDataStoreConfig config) {
        if (config == null || config.location() == null) return NOptional.ofEmpty();
        return NOptional.of(new NaruFileDataStore(config.location()));
    }
}
