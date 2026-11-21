package com.bnaqica.documentreader.listener;

import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.config.NotificationConnectionFactory;
import com.bnaqica.documentreader.service.PgNotificationService;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Holds one dedicated connection that LISTENs on {@code outbox_ready}, {@code inbox_ready} and
 * {@code batch_completed}, and passes every notification to {@link PgNotificationService}; it holds no other logic.
 * A dropped connection is reopened with backoff. Runs only when {@code app.document-reader.workers.enabled} is true.
 */
@Slf4j
@Component
public class PgNotificationListener implements SmartLifecycle {

    private static final String[] CHANNELS = {
        PgNotificationService.OUTBOX_READY, PgNotificationService.INBOX_READY, PgNotificationService.BATCH_COMPLETED
    };

    /** How long one read waits; it only bounds how quickly the loop notices a stop. */
    private static final int READ_TIMEOUT_MS = 500;

    /** 60 empty reads of 500 ms: a quiet connection is checked every 30 s. */
    private static final int HEARTBEAT_AFTER_EMPTY_READS = 60;

    private static final Duration BACKOFF_INITIAL = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(30);
    private static final Duration STOP_WAIT = Duration.ofSeconds(5);

    private final NotificationConnectionFactory connections;
    private final PgNotificationService notifications;
    private final boolean enabled;
    private volatile boolean running;
    private Thread loop;

    public PgNotificationListener(
            NotificationConnectionFactory connections,
            PgNotificationService notifications,
            DocumentReaderProperties properties) {
        this.connections = connections;
        this.notifications = notifications;
        this.enabled = properties.workers().enabled();
    }

    @Override
    public synchronized void start() {
        if (!enabled || running) {
            return;
        }
        if (loop != null && loop.isAlive()) {
            log.error("The previous notification listener is still running; not starting a second one");
            return;
        }
        running = true;
        loop = Thread.ofVirtual().name("pg-notification-listener").start(this::listen);
    }

    private void listen() {
        try {
            listenUntilStopped();
        } finally {
            running = false; // even an Error ends the loop with isRunning() false
        }
    }

    private void listenUntilStopped() {
        Duration backoff = BACKOFF_INITIAL;
        boolean connectedBefore = false;
        while (running) {
            try (Connection connection = connections.open()) {
                subscribe(connection);
                log.info("Listening for notifications on {}", String.join(", ", CHANNELS));
                connectedBefore = true;
                notifications.onConnected();
                backoff = BACKOFF_INITIAL;
                receive(connection);
            } catch (SQLException | RuntimeException e) {
                if (!running) {
                    break;
                }
                // driver and server messages hold no borrower data, and tell bad credentials from a network drop
                log.warn(
                        "Notification connection {} ({}: {}); retrying in {}",
                        connectedBefore ? "lost" : "failed",
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        backoff);
                if (!sleep(backoff)) {
                    break;
                }
                backoff = backoff.multipliedBy(2).compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : backoff.multipliedBy(2);
            }
        }
        log.info("Stopped listening for notifications");
    }

    private static void subscribe(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String channel : CHANNELS) {
                statement.execute("LISTEN " + channel);
            }
        }
    }

    /**
     * Reads notifications until stopped. After {@link #HEARTBEAT_AFTER_EMPTY_READS} empty reads it runs a query, so a
     * connection that died silently fails here and is reopened instead of leaving the instance deaf.
     */
    private void receive(Connection connection) throws SQLException {
        PGConnection pg = connection.unwrap(PGConnection.class);
        int emptyReads = 0;
        while (running) {
            PGNotification[] received = pg.getNotifications(READ_TIMEOUT_MS);
            if (received == null || received.length == 0) {
                if (++emptyReads >= HEARTBEAT_AFTER_EMPTY_READS) {
                    heartbeat(connection);
                    emptyReads = 0;
                }
                continue;
            }
            emptyReads = 0;
            for (PGNotification notification : received) {
                try {
                    notifications.onNotification(notification.getName(), notification.getParameter());
                } catch (RuntimeException e) {
                    log.error("Handling a notification on {} failed; continuing", notification.getName(), e);
                }
            }
        }
    }

    private static void heartbeat(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The loop notices within one read timeout and closes its connection; it is interrupted only if it doesn't. */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            loop.join(STOP_WAIT);
            if (loop.isAlive()) {
                loop.interrupt();
                loop.join(STOP_WAIT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        loop = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
