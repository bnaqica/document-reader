package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.MutableClock;
import com.bnaqica.documentreader.MutableClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.dto.response.OutboxMessageRecord;
import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs outside the test transaction, because a claim commits in its own transaction. Every row is removed before
 * each test, so earlier tests' messages can't be claimed here.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, MutableClockConfig.class, InboxMessageServiceImpl.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InboxMessageServiceTests {

    private static final Duration LEASE = Duration.ofMinutes(10);

    @Autowired
    private InboxMessageService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        clock.reset();
        jdbc.sql("DELETE FROM document_reader.inbox_message").update();
        transaction = new TransactionTemplate(transactionManager);
    }

    private OutboxMessageRecord message(MessageType type) {
        return OutboxMessageRecord.builder()
                .id(UUID.randomUUID())
                .messageType(type)
                .aggregateId(UUID.randomUUID())
                .payload(jsonMapper.readTree("{\"documentId\":\"d1\"}"))
                .createdAt(clock.instant())
                .build();
    }

    private OutboxMessageRecord insert(MessageType type, Instant dueAt) {
        OutboxMessageRecord message = message(type);
        transaction.executeWithoutResult(status -> service.insertIfAbsent(List.of(message), dueAt));
        return message;
    }

    private List<InboxMessageRecord> claim(MessageType type, int limit) {
        return service.claimDue(type, limit, "instance-a", LEASE);
    }

    @Test
    void insertedMessageIsPendingAndDue() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());

        InboxMessageRecord stored = service.get(message.id());
        assertThat(stored.status()).isEqualTo(InboxStatus.PENDING);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.nextAttemptAt()).isEqualTo(clock.instant());
        assertThat(stored.messageType()).isEqualTo(MessageType.CLASSIFY_DOCUMENT);
        assertThat(stored.aggregateId()).isEqualTo(message.aggregateId());
        assertThat(stored.payload().get("documentId").asString()).isEqualTo("d1");
    }

    @Test
    void insertingTheSameIdAgainKeepsOneRow() {
        OutboxMessageRecord message = message(MessageType.SEPARATE_FILE);

        List<MessageType> first =
                transaction.execute(status -> service.insertIfAbsent(List.of(message), clock.instant()));
        List<MessageType> second =
                transaction.execute(status -> service.insertIfAbsent(List.of(message), clock.instant()));

        assertThat(first).containsExactly(MessageType.SEPARATE_FILE);
        assertThat(second).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM document_reader.inbox_message")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void insertRequiresTransaction() {
        assertThatThrownBy(() -> service.insertIfAbsent(List.of(message(MessageType.SEPARATE_FILE)), clock.instant()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void claimTakesItsTypeInDueOrderUpToTheLimit() {
        OutboxMessageRecord later =
                insert(MessageType.CLASSIFY_DOCUMENT, clock.instant().minusSeconds(10));
        OutboxMessageRecord earliest =
                insert(MessageType.CLASSIFY_DOCUMENT, clock.instant().minusSeconds(30));
        insert(MessageType.CLASSIFY_DOCUMENT, clock.instant().minusSeconds(5));
        insert(MessageType.EXTRACT_DOCUMENT, clock.instant().minusSeconds(60));

        List<InboxMessageRecord> claimed = claim(MessageType.CLASSIFY_DOCUMENT, 2);

        assertThat(claimed).extracting(InboxMessageRecord::id).containsExactly(earliest.id(), later.id());
        assertThat(claimed).allSatisfy(message -> {
            assertThat(message.status()).isEqualTo(InboxStatus.PROCESSING);
            assertThat(message.lockedBy()).isEqualTo("instance-a");
            assertThat(message.lockedUntil()).isEqualTo(clock.instant().plus(LEASE));
            assertThat(message.attempts()).isEqualTo(1);
        });
        assertThat(service.get(earliest.id()).status()).isEqualTo(InboxStatus.PROCESSING);
    }

    @Test
    void messageNotDueYetIsNotClaimed() {
        insert(MessageType.CLASSIFY_DOCUMENT, clock.instant().plusSeconds(30));

        assertThat(claim(MessageType.CLASSIFY_DOCUMENT, 10)).isEmpty();
    }

    @Test
    void claimedMessageIsNotClaimedAgainWhileLeased() {
        insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        claim(MessageType.CLASSIFY_DOCUMENT, 10);

        clock.advance(LEASE.minusSeconds(1));

        assertThat(claim(MessageType.CLASSIFY_DOCUMENT, 10)).isEmpty();
    }

    @Test
    void staleLeaseIsReclaimed() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        claim(MessageType.CLASSIFY_DOCUMENT, 10);

        clock.advance(LEASE.plusSeconds(1));
        List<InboxMessageRecord> reclaimed = service.claimDue(MessageType.CLASSIFY_DOCUMENT, 10, "instance-b", LEASE);

        assertThat(reclaimed).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo(message.id());
            assertThat(m.lockedBy()).isEqualTo("instance-b");
            assertThat(m.attempts()).isEqualTo(2);
        });
    }

    @Test
    void doneIsRecordedOnceAndNeverClaimedAgain() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        claim(MessageType.CLASSIFY_DOCUMENT, 10);

        transaction.executeWithoutResult(status -> service.markDone(message.id()));
        clock.advance(Duration.ofMinutes(1));
        transaction.executeWithoutResult(status -> service.markDone(message.id()));

        InboxMessageRecord done = service.get(message.id());
        assertThat(done.status()).isEqualTo(InboxStatus.DONE);
        assertThat(done.completedAt()).isEqualTo(MutableClock.START);
        clock.advance(LEASE.multipliedBy(2));
        assertThat(claim(MessageType.CLASSIFY_DOCUMENT, 10)).isEmpty();
    }

    @Test
    void markDoneRequiresTransaction() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());

        assertThatThrownBy(() -> service.markDone(message.id())).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void retryRecordsTheErrorAndReleasesTheLease() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        InboxMessageRecord claimed = claim(MessageType.CLASSIFY_DOCUMENT, 10).getFirst();

        assertThat(service.markRetry(claimed, "timeout", clock.instant().plusSeconds(10)))
                .isTrue();

        InboxMessageRecord retried = service.get(message.id());
        assertThat(retried.status()).isEqualTo(InboxStatus.PENDING);
        assertThat(retried.lastError()).isEqualTo("timeout");
        assertThat(retried.nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(10));
        assertThat(retried.lockedBy()).isNull();
        assertThat(retried.lockedUntil()).isNull();
    }

    @Test
    void failedRecordsTheErrorCutTo2000Characters() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        InboxMessageRecord claimed = claim(MessageType.CLASSIFY_DOCUMENT, 10).getFirst();

        assertThat(service.markFailed(claimed, "x".repeat(5000))).isTrue();

        InboxMessageRecord failed = service.get(message.id());
        assertThat(failed.status()).isEqualTo(InboxStatus.FAILED);
        assertThat(failed.lastError()).hasSize(2000);
        assertThat(failed.completedAt()).isEqualTo(clock.instant());
    }

    @Test
    void retryOrFailOfADoneMessageChangesNothing() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        InboxMessageRecord claimed = claim(MessageType.CLASSIFY_DOCUMENT, 10).getFirst();
        transaction.executeWithoutResult(status -> service.markDone(message.id()));

        assertThat(service.markRetry(claimed, "late error", clock.instant())).isFalse();
        assertThat(service.markFailed(claimed, "late error")).isFalse();

        assertThat(service.get(message.id()).status()).isEqualTo(InboxStatus.DONE);
        assertThat(service.get(message.id()).lastError()).isNull();
    }

    @Test
    void staleHolderCannotRetryAMessageAnotherInstanceReclaimed() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        InboxMessageRecord stale = claim(MessageType.CLASSIFY_DOCUMENT, 10).getFirst();
        clock.advance(LEASE.plusSeconds(1));
        service.claimDue(MessageType.CLASSIFY_DOCUMENT, 10, "instance-b", LEASE);

        assertThat(service.markRetry(stale, "timeout", clock.instant())).isFalse();
        assertThat(service.markFailed(stale, "timeout")).isFalse();

        InboxMessageRecord current = service.get(message.id());
        assertThat(current.status()).isEqualTo(InboxStatus.PROCESSING);
        assertThat(current.lockedBy()).isEqualTo("instance-b");
        assertThat(current.attempts()).isEqualTo(2);
    }

    @Test
    void doneNeverRevivesAFailedMessage() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        InboxMessageRecord claimed = claim(MessageType.CLASSIFY_DOCUMENT, 10).getFirst();
        service.markFailed(claimed, "gave up");

        transaction.executeWithoutResult(status -> service.markDone(message.id()));

        assertThat(service.get(message.id()).status()).isEqualTo(InboxStatus.FAILED);
    }

    @Test
    void releasePutsTheMessageBackWithoutCountingTheAttempt() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        InboxMessageRecord claimed = claim(MessageType.CLASSIFY_DOCUMENT, 10).getFirst();
        clock.advance(Duration.ofSeconds(30));

        assertThat(service.release(claimed)).isTrue();

        InboxMessageRecord released = service.get(message.id());
        assertThat(released.status()).isEqualTo(InboxStatus.PENDING);
        assertThat(released.attempts()).isZero();
        assertThat(released.nextAttemptAt()).isEqualTo(clock.instant());
        assertThat(released.lockedBy()).isNull();
        assertThat(service.release(claimed)).isFalse();
    }

    @Test
    void recordErrorKeepsTheClaim() {
        OutboxMessageRecord message = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        InboxMessageRecord claimed = claim(MessageType.CLASSIFY_DOCUMENT, 10).getFirst();

        assertThat(service.recordError(claimed, "model refused")).isTrue();

        InboxMessageRecord current = service.get(message.id());
        assertThat(current.lastError()).isEqualTo("model refused");
        assertThat(current.status()).isEqualTo(InboxStatus.PROCESSING);
        assertThat(current.lockedBy()).isEqualTo("instance-a");
    }

    @Test
    void findFiltersByTypeAndStatusNewestFirst() {
        insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        clock.advance(Duration.ofSeconds(1));
        OutboxMessageRecord newer = insert(MessageType.CLASSIFY_DOCUMENT, clock.instant());
        insert(MessageType.EXTRACT_DOCUMENT, clock.instant());

        assertThat(service.find(MessageType.CLASSIFY_DOCUMENT, InboxStatus.PENDING, 1))
                .extracting(InboxMessageRecord::id)
                .containsExactly(newer.id());
        assertThat(service.find(null, null, 50)).hasSize(3);
        assertThat(service.find(null, InboxStatus.FAILED, 50)).isEmpty();
    }

    @Test
    void unknownMessageIsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }
}
