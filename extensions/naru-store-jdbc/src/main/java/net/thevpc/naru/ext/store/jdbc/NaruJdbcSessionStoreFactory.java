package net.thevpc.naru.ext.store.jdbc;

import net.thevpc.naru.api.store.NaruSessionStore;
import net.thevpc.naru.api.store.NaruSessionStoreFactory;
import net.thevpc.naru.api.store.NaruStoreConfig;
import net.thevpc.nuts.util.NOptional;
import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Selectable JDBC implementation of the complete session-store contract. */
public final class NaruJdbcSessionStoreFactory implements NaruSessionStoreFactory {
    private final DataSource dataSource;

    public NaruJdbcSessionStoreFactory() { this(null); }

    /** Creates a session-store factory bound to an application-managed DataSource. */
    public NaruJdbcSessionStoreFactory(DataSource dataSource) { this.dataSource = dataSource; }

    @Override public String name() { return "jdbc"; }

    @Override
    public NOptional<NaruSessionStore> open(NaruStoreConfig config) {
        if (config == null) return NOptional.ofEmpty();
        var provider = new NaruJdbcDataStoreProvider(dataSource);
        // A shared DataSource may serve multiple workspaces. Keep each workspace's
        // sessions separate while the session store itself enforces public/private
        // separation within that workspace.
        var dataStoreConfig = new net.thevpc.naru.api.store.NaruDataStoreConfig(
                "sessions_" + projectHash(config), config.projectDir(), config.projectDir(), config.storeDir().resolve("jdbc"),
                config.properties());
        return provider.open(dataStoreConfig).map(s -> new NaruJdbcSessionStore(s, config));
    }

    private static String projectHash(NaruStoreConfig config) {
        String project = config.projectDir() == null ? config.storeDir().toString() : config.projectDir().toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(project.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
