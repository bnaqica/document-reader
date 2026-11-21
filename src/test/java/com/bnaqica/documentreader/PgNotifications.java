package com.bnaqica.documentreader;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/** A dedicated connection that LISTENs on one channel, so tests can see which NOTIFYs were delivered. */
public final class PgNotifications implements AutoCloseable {

    private final Connection connection;
    private final PGConnection pg;

    public PgNotifications(DataSource dataSource, String channel) throws SQLException {
        this.connection = dataSource.getConnection();
        this.connection.setAutoCommit(true);
        this.pg = connection.unwrap(PGConnection.class);
        try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + channel);
        }
    }

    /** Payloads delivered within {@code millis}; waits the full time only when none arrive. */
    public List<String> receive(int millis) throws SQLException {
        List<String> payloads = new ArrayList<>();
        PGNotification[] notifications = pg.getNotifications(millis);
        if (notifications != null) {
            for (PGNotification notification : notifications) {
                payloads.add(notification.getParameter());
            }
        }
        return payloads;
    }

    @Override
    public void close() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("UNLISTEN *");
        }
        connection.close();
    }
}
