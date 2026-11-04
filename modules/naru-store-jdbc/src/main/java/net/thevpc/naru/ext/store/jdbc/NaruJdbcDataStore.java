package net.thevpc.naru.ext.store.jdbc;

import net.thevpc.naru.api.store.NaruDataStore;
import net.thevpc.naru.api.store.NaruDataStoreWork;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.util.NOptional;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Transactional durable TSON record store with a portable schema for H2 and PostgreSQL. */
final class NaruJdbcDataStore implements NaruDataStore {
    private static final String TABLE = "naru_data_store";
    private final String namespace;
    private final DataSource dataSource;
    private final ThreadLocal<Connection> transaction = new ThreadLocal<>();

    NaruJdbcDataStore(String namespace, DataSource dataSource) {
        this.namespace = namespace;
        this.dataSource = dataSource;
        initialize();
    }

    private void initialize() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " (namespace VARCHAR(255) NOT NULL, record_key VARCHAR(900) NOT NULL, value_tson TEXT NOT NULL, PRIMARY KEY(namespace, record_key))");
        } catch (SQLException e) { throw failure("initialize JDBC data store", e); }
    }

    @Override public NOptional<NElement> get(String key) {
        validateKey(key);
        return withConnection(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT value_tson FROM " + TABLE + " WHERE namespace=? AND record_key=?")) {
                p.setString(1, namespace); p.setString(2, key);
                try (ResultSet rs = p.executeQuery()) {
                    return rs.next() ? NOptional.of(NElementReader.ofTson().read(rs.getString(1))) : NOptional.ofEmpty();
                }
            }
        });
    }

    @Override public void put(String key, NElement value) {
        validateKey(key); Objects.requireNonNull(value, "value");
        transaction(store -> {
            try (PreparedStatement p = current().prepareStatement("UPDATE " + TABLE + " SET value_tson=? WHERE namespace=? AND record_key=?")) {
                p.setString(1, NElementWriter.ofTson().ntf(false).formatPlain(value)); p.setString(2, namespace); p.setString(3, key);
                if (p.executeUpdate() == 0) try (PreparedStatement i = current().prepareStatement("INSERT INTO " + TABLE + " (namespace,record_key,value_tson) VALUES (?,?,?)")) {
                    i.setString(1, namespace); i.setString(2, key); i.setString(3, NElementWriter.ofTson().ntf(false).formatPlain(value)); i.executeUpdate();
                }
            } catch (SQLException e) { throw failure("write JDBC data-store record " + key, e); }
            return null;
        });
    }

    @Override public boolean delete(String key) {
        validateKey(key);
        return transaction(store -> {
            try (PreparedStatement p = current().prepareStatement("DELETE FROM " + TABLE + " WHERE namespace=? AND record_key=?")) {
                p.setString(1, namespace); p.setString(2, key); return p.executeUpdate() != 0;
            } catch (SQLException e) { throw failure("delete JDBC data-store record " + key, e); }
        });
    }

    @Override public List<String> keys(String prefix) {
        String pfx = prefix == null ? "" : prefix;
        return withConnection(c -> {
            List<String> result = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT record_key FROM " + TABLE + " WHERE namespace=? ORDER BY record_key")) {
                p.setString(1, namespace);
                try (ResultSet rs = p.executeQuery()) { while (rs.next()) { String key = rs.getString(1); if (key.startsWith(pfx)) result.add(key); } }
            }
            return List.copyOf(result);
        });
    }

    @Override public <T> T transaction(NaruDataStoreWork<T> work) {
        Objects.requireNonNull(work, "work");
        if (transaction.get() != null) return work.run(this);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false); transaction.set(c);
            try { T result = work.run(this); c.commit(); return result; }
            catch (Throwable e) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                if (e instanceof RuntimeException re) throw re;
                if (e instanceof Error er) throw er;
                throw new IllegalStateException(e);
            } finally { transaction.remove(); }
        } catch (SQLException e) { throw failure("run JDBC data-store transaction", e); }
    }

    private Connection current() {
        Connection c = transaction.get();
        if (c == null) throw new IllegalStateException("JDBC operation requires a transaction");
        return c;
    }

    private <T> T withConnection(SqlWork<T> work) {
        Connection tx = transaction.get();
        if (tx != null) {
            try { return work.run(tx); } catch (SQLException e) { throw failure("read JDBC data store", e); }
        }
        try (Connection c = dataSource.getConnection()) { return work.run(c); }
        catch (SQLException e) { throw failure("read JDBC data store", e); }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 900) throw new IllegalArgumentException("invalid JDBC data-store key");
    }
    private static IllegalStateException failure(String action, SQLException e) { return new IllegalStateException("cannot " + action + ": " + e.getMessage(), e); }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection c) throws SQLException; }
}
