package net.thevpc.naru.ext.store.jdbc;

import net.thevpc.naru.api.store.NaruDataStore;
import net.thevpc.naru.api.store.NaruDataStoreConfig;
import net.thevpc.naru.api.store.NaruDataStoreProvider;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import javax.sql.DataSource;
import java.nio.file.Path;

/** Shared JDBC backend. H2 is embedded by default; any JDBC-compliant URL, including PostgreSQL, is supported. */
public final class NaruJdbcDataStoreProvider implements NaruDataStoreProvider {
    private final DataSource suppliedDataSource;

    public NaruJdbcDataStoreProvider() {
        this(null);
    }

    /** Creates a provider bound to an application-managed DataSource. */
    public NaruJdbcDataStoreProvider(DataSource suppliedDataSource) {
        this.suppliedDataSource = suppliedDataSource;
    }

    @Override public String name() { return "jdbc"; }

    @Override
    public NOptional<NaruDataStore> open(NaruDataStoreConfig config) {
        if (config == null) return NOptional.ofEmpty();
        DataSource ds = suppliedDataSource;
        if (ds == null) {
            String url = config.property("naru.jdbc.url");
            if (url == null || url.isBlank()) {
                NPath dbPath = config.location().resolve("naru-store");
                dbPath.mkParentDirs();
                Path localPath = dbPath.toPath().get();
                url = "jdbc:h2:file:" + localPath.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
            }
            ds = new NaruDriverManagerDataSource(url, config.property("naru.jdbc.user"),
                    config.property("naru.jdbc.password"), config.property("naru.jdbc.driver"));
        }
        return NOptional.of(new NaruJdbcDataStore(config.namespace(), ds));
    }
}
