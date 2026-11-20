package net.thevpc.naru.ext.store.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/** Minimal DataSource adapter for configured JDBC URLs; callers may supply a pooled DataSource instead. */
final class NaruDriverManagerDataSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;

    NaruDriverManagerDataSource(String url, String user, String password, String driver) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("naru.jdbc.url is required");
        this.url = url;
        this.user = user;
        this.password = password;
        if (driver != null && !driver.isBlank()) {
            try { Class.forName(driver); }
            catch (ClassNotFoundException e) { throw new IllegalStateException("JDBC driver not found: " + driver, e); }
        }
    }

    @Override public Connection getConnection() throws SQLException {
        return user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password == null ? "" : password);
    }
    @Override public Connection getConnection(String username, String pwd) throws SQLException { return DriverManager.getConnection(url, username, pwd); }
    @Override public PrintWriter getLogWriter() throws SQLException { return DriverManager.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { DriverManager.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { DriverManager.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return DriverManager.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { if (iface.isInstance(this)) return iface.cast(this); throw new SQLException("not a wrapper for " + iface); }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
