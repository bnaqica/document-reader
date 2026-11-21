package com.bnaqica.documentreader.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.config.NotificationConnectionConfig;
import com.bnaqica.documentreader.config.NotificationConnectionFactory;
import com.bnaqica.documentreader.service.PgNotificationService;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The listener runs for real against Postgres (workers switched on for this context only), with the routing service
 * mocked. NOTIFYs are sent from the test's own autocommit connection.
 */
@DataJpaTest(properties = "app.document-reader.workers.enabled=true")
@Import({TestcontainersConfiguration.class, NotificationConnectionConfig.class, PgNotificationListener.class})
@EnableConfigurationProperties(DocumentReaderProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PgNotificationListenerTests {

    private static final long WAIT_MS = 5000;

    @MockitoBean
    private PgNotificationService notifications;

    @Autowired
    private PgNotificationListener listener;

    @Autowired
    private NotificationConnectionFactory connections;

    @Autowired
    private DocumentReaderProperties properties;

    @Autowired
    private JdbcClient jdbc;

    /** The listener connects once per context, so probe until it answers, then start each test from a clean mock. */
    @BeforeEach
    void listening() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(WAIT_MS).toNanos();
        while (Mockito.mockingDetails(notifications).getInvocations().stream()
                .noneMatch(call -> call.getArguments().length == 2 && "probe".equals(call.getArguments()[1]))) {
            assertThat(System.nanoTime()).as("listener never answered").isLessThan(deadline);
            notify("outbox_ready", "probe");
            Thread.sleep(100);
        }
        Mockito.clearInvocations(notifications);
    }

    private void notify(String channel, String payload) {
        jdbc.sql("SELECT pg_notify(?, ?)").params(channel, payload).query().singleRow();
    }

    @Test
    void listenerOnlyDelegates() throws InterruptedException {
        String batch = UUID.randomUUID().toString();

        notify("outbox_ready", "");
        notify("inbox_ready", "EXTRACT_DOCUMENT");
        notify("batch_completed", batch);

        verify(notifications, timeout(WAIT_MS)).onNotification("outbox_ready", "");
        verify(notifications, timeout(WAIT_MS)).onNotification("inbox_ready", "EXTRACT_DOCUMENT");
        verify(notifications, timeout(WAIT_MS)).onNotification("batch_completed", batch);

        // the timeouts above pass at the first call; settle, then prove there was exactly one of each
        Thread.sleep(300);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void dependsOnTheFactoryTheServiceAndTheSettingsOnly() {
        Constructor<?>[] constructors = PgNotificationListener.class.getConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Arrays.stream(constructors[0].getParameterTypes()).map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "NotificationConnectionFactory", "PgNotificationService", "DocumentReaderProperties");
    }

    @Test
    void listenerReconnectsAfterConnectionDrop() {
        int terminated = jdbc.sql("""
                        SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity
                        WHERE application_name = ?
                        """)
                .param(NotificationConnectionFactory.APPLICATION_NAME)
                .query(Integer.class)
                .single();
        assertThat(terminated).isEqualTo(1);

        verify(notifications, timeout(WAIT_MS)).onConnected();
        notify("outbox_ready", "");
        verify(notifications, timeout(WAIT_MS)).onNotification("outbox_ready", "");
    }

    @Test
    void stopClosesTheConnection() throws Exception {
        listener.stop();
        try {
            assertThat(listener.isRunning()).isFalse();
            assertThat(listeningConnections()).isZero();
        } finally {
            listener.start();
        }
    }

    @Test
    void secondStartDoesNotStartASecondListener() {
        listener.start();

        assertThat(listeningConnectionsNow()).isEqualTo(1);
    }

    @Test
    void disabledListenerOpensNothing() {
        NotificationConnectionFactory unused = mock(NotificationConnectionFactory.class);
        PgNotificationService service = mock(PgNotificationService.class);
        PgNotificationListener disabled = new PgNotificationListener(unused, service, withWorkers(false));

        disabled.start();

        assertThat(disabled.isRunning()).isFalse();
        verifyNoInteractions(unused);
        verify(service, never()).onNotification(anyString(), anyString());
    }

    private int listeningConnectionsNow() {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE application_name = ?")
                .param(NotificationConnectionFactory.APPLICATION_NAME)
                .query(Integer.class)
                .single();
    }

    private int listeningConnections() throws InterruptedException {
        // the backend goes away shortly after the client closes
        for (int i = 0; i < 50; i++) {
            int count = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE application_name = ?")
                    .param(NotificationConnectionFactory.APPLICATION_NAME)
                    .query(Integer.class)
                    .single();
            if (count == 0) {
                return 0;
            }
            Thread.sleep(Duration.ofMillis(100));
        }
        return 1;
    }

    private DocumentReaderProperties withWorkers(boolean enabled) {
        return new DocumentReaderProperties(
                properties.batch(),
                properties.file(),
                properties.conversion(),
                properties.separation(),
                properties.classification(),
                properties.extraction(),
                properties.openAi(),
                properties.waitSettings(),
                properties.outbox(),
                properties.inbox(),
                properties.webhook(),
                properties.s3(),
                new DocumentReaderProperties.Workers(enabled));
    }
}
