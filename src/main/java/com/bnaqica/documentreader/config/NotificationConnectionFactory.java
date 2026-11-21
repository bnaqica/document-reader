package com.bnaqica.documentreader.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens the connection that LISTENs for notifications. It sits outside the connection pool: a pool connection held
 * forever would shrink the pool and be retired by the pool's maximum lifetime.
 */
public class NotificationConnectionFactory {

    /** Shown in {@code pg_stat_activity}, so the listening connection is easy to find. */
    public static final String APPLICATION_NAME = "document-reader-listener";

    private final String url;
    private final Properties credentials = new Properties();

    public NotificationConnectionFactory(String url, String username, String password) {
        this.url = url;
        if (username != null) {
            credentials.setProperty("user", username);
        }
        if (password != null) {
            credentials.setProperty("password", password);
        }
        credentials.setProperty("ApplicationName", APPLICATION_NAME);
        // a connection dropped without a reset (an idle NAT or load balancer) is noticed instead of going quiet
        credentials.setProperty("tcpKeepAlive", "true");
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url, credentials);
        connection.setAutoCommit(true);
        return connection;
    }
}
