package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bnaqica.documentreader.PgNotifications;
import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits for real: a wait reads the batch from other transactions. A short poll interval keeps the tests quick. */
@DataJpaTest(properties = "app.document-reader.wait.poll-interval=500ms")
@Import({
    TestcontainersConfiguration.class,
    StepClockConfig.class,
    CompletionWaitServiceImpl.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class
})
@EnableConfigurationProperties(DocumentReaderProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CompletionWaitServiceTests {

    private static final Duration POLL = Duration.ofMillis(500);

    @Autowired
    private CompletionWaitService waits;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ProcessingFixtures fixtures;
    private UUID batch;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        fixtures = new ProcessingFixtures(jdbc);
        batch = fixtures.batch("user-1"); // PROCESSING
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(CompletionWaitServiceImpl.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(CompletionWaitServiceImpl.class)).detachAppender(logs);
        fixtures.deleteBatch(batch);
    }

    private void setStatus(String status) {
        jdbc.sql("UPDATE document_reader.batch SET status = ?, completed_at = now() WHERE id = ?")
                .params(status, batch)
                .update();
    }

    private String status() {
        return jdbc.sql("SELECT status FROM document_reader.batch WHERE id = ?")
                .param(batch)
                .query(String.class)
                .single();
    }

    private static void later(long millis, Runnable action) {
        CompletableFuture.runAsync(action, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
    }

    @Test
    void awaitReturnsWhenNotified() {
        later(200, () -> {
            setStatus("COMPLETED");
            waits.onBatchCompleted(batch);
        });

        long started = System.nanoTime();
        assertThat(waits.await(batch, Duration.ofSeconds(10))).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void awaitFallsBackToPolling() {
        later(200, () -> setStatus("COMPLETED"));

        long started = System.nanoTime();
        assertThat(waits.await(batch, Duration.ofSeconds(10))).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(POLL.plusSeconds(1));
    }

    @Test
    void awaitTimesOut() {
        long started = System.nanoTime();

        assertThat(waits.await(batch, Duration.ofSeconds(3))).isFalse();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(3));
        assertThat(status()).isEqualTo("PROCESSING");
        assertThat(waits.waitingCount()).isZero();
    }

    @Test
    void alreadyFinalBatchReturnsAtOnce() {
        setStatus("FAILED");

        long started = System.nanoTime();
        assertThat(waits.await(batch, Duration.ofSeconds(10))).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void unknownBatchIsNotFound() {
        assertThatThrownBy(() -> waits.await(UUID.randomUUID(), Duration.ofSeconds(1)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(waits.waitingCount()).isZero();
    }

    @Test
    void waiterRemovedWhenCallerInterrupted() throws Exception {
        AtomicBoolean result = new AtomicBoolean(true);
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread caller = Thread.ofVirtual().start(() -> {
            result.set(waits.await(batch, Duration.ofSeconds(30)));
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        while (waits.waitingCount() == 0) {
            Thread.sleep(10);
        }

        caller.interrupt();
        caller.join(Duration.ofSeconds(2));

        assertThat(caller.isAlive()).isFalse();
        assertThat(result).isFalse();
        assertThat(interruptKept).isTrue();
        assertThat(waits.waitingCount()).isZero();
        assertThat(status()).isEqualTo("PROCESSING");
        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
    }

    @Test
    void announcingRequiresTransaction() {
        assertThatThrownBy(() -> waits.notifyBatchCompleted(batch))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void announcedCompletionIsDeliveredOnCommit() throws Exception {
        try (PgNotifications notifications = new PgNotifications(dataSource, "batch_completed")) {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> waits.notifyBatchCompleted(batch));

            assertThat(notifications.receive(2000)).containsExactly(batch.toString());
        }
    }

    @Test
    void announcedCompletionWakesALocalWaiterAfterCommit() {
        later(
                200,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    setStatus("COMPLETED");
                    waits.notifyBatchCompleted(batch);
                }));

        long started = System.nanoTime();
        assertThat(waits.await(batch, Duration.ofSeconds(10))).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void rolledBackCompletionIsNotDelivered() throws Exception {
        try (PgNotifications notifications = new PgNotifications(dataSource, "batch_completed")) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                waits.notifyBatchCompleted(batch);
                status.setRollbackOnly();
            });

            assertThat(notifications.receive(500)).isEmpty();
        }
    }

    /** A stray or early notification isn't trusted: the status must be final too. */
    @Test
    void notificationForABatchStillProcessingDoesNotEndTheWait() {
        later(200, () -> waits.onBatchCompleted(batch));

        assertThat(waits.await(batch, Duration.ofSeconds(2))).isFalse();
    }

    @Test
    void rolledBackAnnouncementDoesNotWakeALocalWaiter() {
        later(
                200,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    waits.notifyBatchCompleted(batch);
                    status.setRollbackOnly();
                }));

        assertThat(waits.await(batch, Duration.ofMillis(1500))).isFalse();
    }

    /** Inside a transaction the batch row may not be committed and re-reads are cached, so a wait could never end. */
    @Test
    void waitingInsideATransactionIsRefused() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> waits.await(batch, Duration.ofSeconds(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        assertThat(waits.waitingCount()).isZero();
    }
}
