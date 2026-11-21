package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.MutableClock;
import com.bnaqica.documentreader.MutableClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.config.WorkerConfig;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Commits for real, because a claim commits before the handler runs. Stub handlers cover {@code CLASSIFY_DOCUMENT}
 * and {@code DELIVER_WEBHOOK}; the other types have none. Defaults from application.yml: 5 attempts (8 for
 * webhooks), backoff 10 s doubling up to 10 min, lease 10 min.
 */
@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    MutableClockConfig.class,
    WorkerConfig.class,
    InboxProcessingServiceImpl.class,
    InboxMessageServiceImpl.class,
    WorkerSignalServiceImpl.class,
    InboxProcessingServiceTests.Handlers.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@EnableConfigurationProperties(DocumentReaderProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InboxProcessingServiceTests {

    @TestConfiguration
    static class Handlers {
        @Bean
        StubHandler classify() {
            return new StubHandler(MessageType.CLASSIFY_DOCUMENT);
        }

        @Bean
        StubHandler webhook() {
            return new StubHandler(MessageType.DELIVER_WEBHOOK);
        }
    }

    @Autowired
    private InboxProcessingService processing;

    @Autowired
    private InboxMessageService inbox;

    @Autowired
    @Qualifier("classify")
    private StubHandler classify;

    @Autowired
    @Qualifier("webhook")
    private StubHandler webhook;

    @Autowired
    private WorkerSignalService signals;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private DocumentReaderProperties properties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void reset() {
        clock.reset();
        classify.reset();
        webhook.reset();
        jdbc.sql("DELETE FROM document_reader.inbox_message").update();
    }

    /** A message of {@code type} that has already used {@code attempts} attempts and is due now. */
    private UUID pending(MessageType type, int attempts) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.inbox_message
                            (id, message_type, aggregate_id, payload, status, attempts, next_attempt_at, created_at)
                        VALUES (?, ?, ?, '{}'::jsonb, 'PENDING', ?, ?, ?)
                        """)
                .params(id, type.name(), UUID.randomUUID(), attempts, at(clock.instant()), at(clock.instant()))
                .update();
        return id;
    }

    /** The Postgres driver binds OffsetDateTime, not Instant. */
    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private InboxMessageRecord message(UUID id) {
        return inbox.get(id);
    }

    @Test
    void successfulMessageIsDone() {
        UUID id = pending(MessageType.CLASSIFY_DOCUMENT, 0);

        assertThat(processing.processDue(MessageType.CLASSIFY_DOCUMENT)).isEqualTo(1);

        assertThat(classify.handled()).extracting(InboxMessageRecord::id).containsExactly(id);
        assertThat(message(id).status()).isEqualTo(InboxStatus.DONE);
        assertThat(message(id).completedAt()).isEqualTo(clock.instant());
    }

    @Test
    void handlerRunsWithNoTransactionOpen() {
        pending(MessageType.CLASSIFY_DOCUMENT, 0);

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);

        assertThat(classify.transactionActive()).containsExactly(false);
    }

    @Test
    void handlerMayMarkItsMessageDoneInItsOwnTransaction() {
        UUID id = pending(MessageType.CLASSIFY_DOCUMENT, 0);
        List<InboxStatus> seenByHandler = new CopyOnWriteArrayList<>();
        classify.whenHandling(message -> {
            transaction().executeWithoutResult(status -> inbox.markDone(message.id()));
            seenByHandler.add(inbox.get(message.id()).status());
        });

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);

        assertThat(seenByHandler).containsExactly(InboxStatus.DONE);
        assertThat(message(id).status()).isEqualTo(InboxStatus.DONE);
    }

    @Test
    void handlerThatMarksDoneThenThrowsIsNotRetried() {
        UUID id = pending(MessageType.CLASSIFY_DOCUMENT, 1);
        classify.whenHandling(doneThenThrow());

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);

        assertThat(message(id).status()).isEqualTo(InboxStatus.DONE);
        assertThat(message(id).lastError()).isNull();
    }

    @Test
    void handlerThatMarksDoneThenThrowsOnItsLastAttemptIsNotExhausted() {
        UUID id = pending(MessageType.CLASSIFY_DOCUMENT, 4);
        classify.whenHandling(doneThenThrow());

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);

        assertThat(message(id).status()).isEqualTo(InboxStatus.DONE);
        assertThat(classify.exhausted()).isEmpty();
    }

    /** Shutdown interrupts a handler: the claim is put back without counting, even on the last attempt. */
    @Test
    void interruptedHandlerReleasesItsClaimWithoutCountingTheAttempt() {
        UUID id = pending(MessageType.CLASSIFY_DOCUMENT, 4);
        classify.whenHandling(message -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("connection closed by interrupt");
        });

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);

        assertThat(message(id).status()).isEqualTo(InboxStatus.PENDING);
        assertThat(message(id).attempts()).isEqualTo(4);
        assertThat(message(id).nextAttemptAt()).isEqualTo(clock.instant());
        assertThat(classify.exhausted()).isEmpty();
    }

    @Test
    void fullClaimSignalsItsTypeToDrainTheBacklog() {
        signals.awaitSignal(MessageType.CLASSIFY_DOCUMENT, Duration.ZERO);
        for (int i = 0; i < properties.inbox().concurrency() + 1; i++) {
            pending(MessageType.CLASSIFY_DOCUMENT, 0);
        }

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);
        assertThat(signals.awaitSignal(MessageType.CLASSIFY_DOCUMENT, Duration.ZERO))
                .isTrue();

        processing.processDue(MessageType.CLASSIFY_DOCUMENT); // the one left is not a full claim
        assertThat(signals.awaitSignal(MessageType.CLASSIFY_DOCUMENT, Duration.ZERO))
                .isFalse();
    }

    private Consumer<InboxMessageRecord> doneThenThrow() {
        return message -> {
            transaction().executeWithoutResult(status -> inbox.markDone(message.id()));
            throw new IllegalStateException("cleanup after commit failed");
        };
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    void twoWorkersProcessEachMessageExactlyOnce() throws Exception {
        for (int i = 0; i < 50; i++) {
            pending(MessageType.CLASSIFY_DOCUMENT, 0);
        }
        classify.whenHandling(message -> sleep(5));
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> worker = () -> {
            start.await();
            int total = 0;
            int processed;
            while ((processed = processing.processDue(MessageType.CLASSIFY_DOCUMENT)) > 0) {
                total += processed;
            }
            return total;
        };

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = threads.submit(worker);
            Future<Integer> second = threads.submit(worker);
            start.countDown();

            assertThat(first.get() + second.get()).isEqualTo(50);
        }
        List<UUID> ids = classify.handled().stream().map(InboxMessageRecord::id).toList();
        assertThat(ids).hasSize(50).doesNotHaveDuplicates();
    }

    @Test
    void failedHandleSchedulesRetryWithBackoff() {
        classify.failWith("timeout");
        UUID first = pending(MessageType.CLASSIFY_DOCUMENT, 0);
        UUID third = pending(MessageType.CLASSIFY_DOCUMENT, 2);

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);

        Instant now = clock.instant();
        assertThat(message(first).status()).isEqualTo(InboxStatus.PENDING);
        assertThat(message(first).attempts()).isEqualTo(1);
        assertThat(message(first).nextAttemptAt()).isEqualTo(now.plusSeconds(10));
        assertThat(message(first).lastError()).contains("timeout");
        assertThat(message(third).attempts()).isEqualTo(3);
        assertThat(message(third).nextAttemptAt()).isEqualTo(now.plusSeconds(40));
    }

    @Test
    void backoffCapsAtMax() {
        classify.failWith("timeout");
        UUID id = pending(MessageType.CLASSIFY_DOCUMENT, 0);
        jdbc.sql("UPDATE document_reader.inbox_message SET attempts = 10 WHERE id = ?")
                .param(id)
                .update();
        // 10 prior attempts would exhaust a 5-attempt message, so raise the limit for this type only
        processing = new InboxProcessingServiceImpl(
                List.of(classify),
                inbox,
                signals,
                withMaxAttempts(20),
                clock,
                transactionManager,
                new WorkerConfig().workerInstance());

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);

        assertThat(message(id).attempts()).isEqualTo(11);
        assertThat(message(id).nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
    }

    @Test
    void exhaustedAfterMaxAttemptsCallsOnExhausted() {
        classify.failWith("model refused");
        UUID id = pending(MessageType.CLASSIFY_DOCUMENT, 4);

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);

        assertThat(message(id).status()).isEqualTo(InboxStatus.FAILED);
        assertThat(message(id).attempts()).isEqualTo(5);
        assertThat(classify.exhausted()).singleElement().asString().contains("model refused");
        assertThat(message(id).lastError()).contains("model refused");
    }

    @Test
    void webhookUsesItsOwnMaxAttempts() {
        webhook.failWith("503");
        UUID fifth = pending(MessageType.DELIVER_WEBHOOK, 4);
        UUID eighth = pending(MessageType.DELIVER_WEBHOOK, 7);

        processing.processDue(MessageType.DELIVER_WEBHOOK);

        assertThat(message(fifth).status()).isEqualTo(InboxStatus.PENDING);
        assertThat(message(eighth).status()).isEqualTo(InboxStatus.FAILED);
        assertThat(webhook.exhausted()).hasSize(1);
    }

    @Test
    void crashOnTheLastAttemptIsExhaustedWithoutHandling() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.inbox_message
                            (id, message_type, aggregate_id, payload, status, attempts, next_attempt_at,
                             locked_by, locked_until, created_at)
                        VALUES (?, 'CLASSIFY_DOCUMENT', ?, '{}'::jsonb, 'PROCESSING', 5, ?, 'crashed', ?, ?)
                        """)
                .params(
                        id,
                        UUID.randomUUID(),
                        at(clock.instant()),
                        at(clock.instant().minusSeconds(60)),
                        at(clock.instant()))
                .update();

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);

        assertThat(classify.handled()).isEmpty();
        assertThat(classify.exhausted()).hasSize(1);
        assertThat(message(id).status()).isEqualTo(InboxStatus.FAILED);
    }

    @Test
    void onExhaustedThatThrowsLeavesTheMessageClaimed() {
        classify.failWith("model refused");
        classify.whenExhausted(() -> {
            throw new IllegalStateException("database down");
        });
        UUID id = pending(MessageType.CLASSIFY_DOCUMENT, 4);

        processing.processDue(MessageType.CLASSIFY_DOCUMENT);

        assertThat(message(id).status()).isEqualTo(InboxStatus.PROCESSING);
        // the error survives the rolled-back exhaust, so the retried exhaust reports it
        assertThat(message(id).lastError()).contains("model refused");
    }

    @Test
    void typeWithoutAHandlerStaysPending() {
        UUID id = pending(MessageType.EXTRACT_DOCUMENT, 0);

        assertThat(processing.processDue(MessageType.EXTRACT_DOCUMENT)).isZero();

        assertThat(message(id).status()).isEqualTo(InboxStatus.PENDING);
        assertThat(message(id).attempts()).isZero();
        assertThat(processing.handledTypes())
                .containsExactlyInAnyOrder(MessageType.CLASSIFY_DOCUMENT, MessageType.DELIVER_WEBHOOK);
    }

    @Test
    void twoHandlersForOneTypeAreRefused() {
        List<InboxMessageHandler> handlers =
                List.of(new StubHandler(MessageType.EXTRACT_DOCUMENT), new StubHandler(MessageType.EXTRACT_DOCUMENT));

        assertThatThrownBy(() -> new InboxProcessingServiceImpl(
                        handlers,
                        inbox,
                        signals,
                        properties,
                        clock,
                        transactionManager,
                        new WorkerConfig().workerInstance()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXTRACT_DOCUMENT");
    }

    @Test
    void maxAttemptsIsPerType() {
        assertThat(processing.maxAttempts(MessageType.CLASSIFY_DOCUMENT)).isEqualTo(5);
        assertThat(processing.maxAttempts(MessageType.DELIVER_WEBHOOK)).isEqualTo(8);
    }

    private DocumentReaderProperties withMaxAttempts(int maxAttempts) {
        DocumentReaderProperties.Inbox current = properties.inbox();
        DocumentReaderProperties.Inbox inboxSettings = new DocumentReaderProperties.Inbox(
                maxAttempts, current.backoffInitial(), current.backoffMax(), current.lease(), current.concurrency());
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
                inboxSettings,
                properties.webhook(),
                properties.s3(),
                properties.workers());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
