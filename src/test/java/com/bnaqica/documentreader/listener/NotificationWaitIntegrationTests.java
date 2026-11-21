package com.bnaqica.documentreader.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.config.NotificationConnectionConfig;
import com.bnaqica.documentreader.service.BatchServiceImpl;
import com.bnaqica.documentreader.service.CompletionWaitService;
import com.bnaqica.documentreader.service.CompletionWaitServiceImpl;
import com.bnaqica.documentreader.service.LoanPurposeServiceImpl;
import com.bnaqica.documentreader.service.PgNotificationServiceImpl;
import com.bnaqica.documentreader.service.WorkerSignalServiceImpl;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole chain: a NOTIFY from another connection reaches the listener, the routing service and the waiter. The
 * poll interval is a minute, so only the notification can end the wait in time.
 */
@DataJpaTest(properties = {"app.document-reader.workers.enabled=true", "app.document-reader.wait.poll-interval=60s"})
@Import({
    TestcontainersConfiguration.class,
    StepClockConfig.class,
    NotificationConnectionConfig.class,
    PgNotificationListener.class,
    PgNotificationServiceImpl.class,
    WorkerSignalServiceImpl.class,
    CompletionWaitServiceImpl.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class
})
@EnableConfigurationProperties(DocumentReaderProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationWaitIntegrationTests {

    @Autowired
    private CompletionWaitService waits;

    @Autowired
    private JdbcClient jdbc;

    private ProcessingFixtures fixtures;
    private UUID batch;

    @BeforeEach
    void setUp() {
        fixtures = new ProcessingFixtures(jdbc);
        batch = fixtures.batch("user-1");
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteBatch(batch);
    }

    @Test
    void notificationFromAnotherConnectionEndsTheWait() {
        CompletableFuture.runAsync(
                () -> {
                    jdbc.sql("UPDATE document_reader.batch SET status = 'COMPLETED' WHERE id = ?")
                            .param(batch)
                            .update();
                    jdbc.sql("SELECT pg_notify('batch_completed', ?)")
                            .param(batch.toString())
                            .query()
                            .singleRow();
                },
                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS));

        long started = System.nanoTime();
        assertThat(waits.await(batch, Duration.ofSeconds(20))).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }
}
