package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.StepClock;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.WebhookStatus;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    BatchServiceTests.ClockConfig.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class
})
class BatchServiceTests {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return new StepClock();
        }
    }

    @Autowired
    private BatchService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void resetClock() {
        ((StepClock) clock).reset();
    }

    private static NewBatch sync(String requestedBy) {
        return new NewBatch("CAR_REFINANCE", false, null, requestedBy, 2);
    }

    @Test
    void syncBatchStartsReceivedWithNoWebhook() {
        BatchRecord batch = service.create(sync("user-1"));

        assertThat(batch.status()).isEqualTo(BatchStatus.RECEIVED);
        assertThat(batch.webhookStatus()).isEqualTo(WebhookStatus.NOT_APPLICABLE);
        assertThat(batch.loanPurposeCode()).isEqualTo("CAR_REFINANCE");
        assertThat(batch.fileCount()).isEqualTo(2);
        assertThat(batch.requestedBy()).isEqualTo("user-1");
        assertThat(batch.receivedAt()).isEqualTo(StepClock.START);
        assertThat(batch.completedAt()).isNull();
        assertThat(service.get(batch.id())).isEqualTo(batch);
    }

    @Test
    void asyncBatchStartsWithWebhookPending() {
        BatchRecord batch =
                service.create(new NewBatch("HOME_PURCHASE", true, "https://example.com/hook", "user-1", 1));

        assertThat(batch.async()).isTrue();
        assertThat(batch.webhookUrl()).isEqualTo("https://example.com/hook");
        assertThat(batch.webhookStatus()).isEqualTo(WebhookStatus.PENDING);
    }

    @Test
    void unknownLoanPurposeIsRefusedAndNothingIsStored() {
        assertThatThrownBy(() -> service.create(new NewBatch("SPACESHIP_PURCHASE", false, null, "user-1", 1)))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM document_reader.batch")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void completingRecordsFinalStatusAndTime() {
        BatchRecord batch = service.create(sync("user-1"));
        service.updateStatus(batch.id(), BatchStatus.PROCESSING, null);
        Instant done = Instant.parse("2026-11-20T16:05:00Z");

        service.complete(batch.id(), BatchStatus.PARTIALLY_COMPLETED, null, done);

        BatchRecord completed = service.get(batch.id());
        assertThat(completed.status()).isEqualTo(BatchStatus.PARTIALLY_COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(done);
    }

    @Test
    void completingWithAStatusThatIsNotFinalIsRefused() {
        BatchRecord batch = service.create(sync("user-1"));

        assertThatThrownBy(() -> service.complete(batch.id(), BatchStatus.PROCESSING, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(batch.id()).completedAt()).isNull();
    }

    @Test
    void completingTwiceIsRefusedAndTheFirstStatusStays() {
        BatchRecord batch = service.create(sync("user-1"));
        service.complete(batch.id(), BatchStatus.COMPLETED, null, Instant.parse("2026-11-20T16:05:00Z"));

        assertThatThrownBy(() ->
                        service.complete(batch.id(), BatchStatus.FAILED, "late", Instant.parse("2026-11-20T16:06:00Z")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.get(batch.id()).status()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void updatesWebhookStatus() {
        BatchRecord batch =
                service.create(new NewBatch("HOME_PURCHASE", true, "https://example.com/hook", "user-1", 1));

        service.updateWebhookStatus(batch.id(), WebhookStatus.DELIVERED);

        assertThat(service.get(batch.id()).webhookStatus()).isEqualTo(WebhookStatus.DELIVERED);
    }

    @Test
    void findRecentFiltersByRequesterNewestFirst() {
        BatchRecord first = service.create(sync("user-1"));
        BatchRecord other = service.create(sync("user-2"));
        BatchRecord second = service.create(sync("user-1"));

        assertThat(service.findRecent("user-1", 50))
                .extracting(BatchRecord::id)
                .containsExactly(second.id(), first.id());
        assertThat(service.findRecent(null, 50))
                .extracting(BatchRecord::id)
                .containsExactly(second.id(), other.id(), first.id());
        assertThat(service.findRecent(null, 2)).hasSize(2);
    }

    @Test
    void unknownBatchIsNotFound() {
        assertThatThrownBy(() -> service.get(java.util.UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStatusCannotSetAFinalStatus() {
        BatchRecord batch = service.create(sync("user-1"));

        assertThatThrownBy(() -> service.updateStatus(batch.id(), BatchStatus.COMPLETED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(batch.id()).status()).isEqualTo(BatchStatus.RECEIVED);
    }

    @Test
    void completedBatchCannotBeReopened() {
        BatchRecord batch = service.create(sync("user-1"));
        service.complete(batch.id(), BatchStatus.COMPLETED, null, Instant.parse("2026-11-20T16:05:00Z"));

        assertThatThrownBy(() -> service.updateStatus(batch.id(), BatchStatus.PROCESSING, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.get(batch.id()).status()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void failingABatchRecordsItsReasonAndCompletingClearsAnOldOne() {
        BatchRecord failed = service.create(sync("user-1"));
        service.complete(failed.id(), BatchStatus.FAILED, "S3 upload failed", Instant.parse("2026-11-20T16:05:00Z"));
        assertThat(service.get(failed.id()).failureReason()).isEqualTo("S3 upload failed");

        BatchRecord completed = service.create(sync("user-1"));
        service.complete(completed.id(), BatchStatus.COMPLETED, "ignored", Instant.parse("2026-11-20T16:05:00Z"));
        assertThat(service.get(completed.id()).failureReason()).isNull();
    }

    @Test
    void batchesReceivedAtTheSameMomentListInAStableOrder() {
        java.util.UUID low = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        java.util.UUID high = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002");
        for (java.util.UUID id : java.util.List.of(low, high)) {
            jdbc.sql("""
                            INSERT INTO document_reader.batch
                                (id, loan_purpose_code, async, status, webhook_status, file_count, requested_by, received_at)
                            VALUES (?, 'CAR_REFINANCE', false, 'RECEIVED', 'NOT_APPLICABLE', 1, 'user-9', '2026-11-20T16:00:00Z')
                            """).param(id).update();
        }

        assertThat(service.findRecent("user-9", 50)).extracting(BatchRecord::id).containsExactly(high, low);
    }
}
