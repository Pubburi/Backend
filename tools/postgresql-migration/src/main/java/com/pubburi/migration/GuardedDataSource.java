package com.pubburi.migration;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** All Flyway connections retain the same verified TLS and bounded JDBC settings. */
final class GuardedDataSource implements DataSource {
    private final PostgresSchemaTool.Settings settings;
    GuardedDataSource(PostgresSchemaTool.Settings settings) { this.settings = settings; }
    @Override public Connection getConnection() throws SQLException { return settings.connect(); }
    @Override public Connection getConnection(String user, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("Alternate credentials are not supported");
    }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public int getLoginTimeout() { return 3; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
}
