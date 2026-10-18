package net.thevpc.naru.ext.store.jdbc;

import net.thevpc.naru.api.store.NaruSessionStore;
import net.thevpc.naru.api.store.NaruSessionStoreFactory;
import net.thevpc.naru.api.store.NaruStoreConfig;
import net.thevpc.nuts.util.NOptional;

/** Selectable JDBC implementation of the complete session-store contract. */
public final class NaruJdbcSessionStoreFactory implements NaruSessionStoreFactory {
    @Override public String name() { return "jdbc"; }

    @Override
    public NOptional<NaruSessionStore> open(NaruStoreConfig config) {
        if (config == null) return NOptional.ofEmpty();
        var provider = new NaruJdbcDataStoreProvider();
        var dataStoreConfig = new net.thevpc.naru.api.store.NaruDataStoreConfig(
                "sessions", config.projectDir(), config.projectDir(), config.storeDir().resolve("jdbc"),
                config.dataSource(), config.properties());
        return provider.open(dataStoreConfig).map(s -> new NaruJdbcSessionStore(s, config));
    }
}
