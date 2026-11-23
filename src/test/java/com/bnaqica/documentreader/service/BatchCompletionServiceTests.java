package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.PgNotifications;
import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import com.bnaqica.documentreader.entity.WebhookStatus;
import com.bnaqica.documentreader.publisher.OutboxPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The completion check runs in the finishing step's own transaction and locks the batch, so these run real
 * transactions outside the test transaction. They remove their own rows.
 */
@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    ClockConfig.class,
    BatchCompletionServiceImpl.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class,
    SourceFileServiceImpl.class,
    DocumentServiceImpl.class,
    DocumentTypeServiceImpl.class,
    CompletionWaitServiceImpl.class,
    OutboxPublisher.class,
    OutboxMessageServiceImpl.class,
    WorkerSignalServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@EnableConfigurationProperties(DocumentReaderProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BatchCompletionServiceTests {

    @Autowired
    private BatchCompletionService completion;

    @Autowired
    private BatchService batches;

    @Autowired
    private SourceFileService sourceFiles;

    @Autowired
    private DocumentService documents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void removeBatches() {
        ProcessingFixtures fixtures = new ProcessingFixtures(jdbc);
        for (UUID batch : created) {
            jdbc.sql("DELETE FROM document_reader.outbox_message WHERE aggregate_id = ?")
                    .param(batch)
                    .update();
            fixtures.deleteBatch(batch);
        }
    }

    @Test
    void lastDocumentCompletesBatch() {
        UUID batch = processingBatch(false, 2);
        List<UUID> files = files(batch);
        UUID first = separate(files.get(0), 1).getFirst();
        UUID last = separate(files.get(1), 1).getFirst();
        finishDocument(first, DocumentStatus.COMPLETED);

        finishDocument(last, DocumentStatus.COMPLETED);

        BatchRecord completed = batches.get(batch);
        assertThat(completed.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
    }

    @Test
    void doesNotCompleteWhileAFileIsStillSeparating() {
        UUID batch = processingBatch(false, 2);
        List<UUID> files = files(batch);
        List<UUID> file0 = separate(files.get(0), 2);
        sourceFiles.updateStatus(files.get(1), SourceFileStatus.SEPARATING, null);

        finishDocument(file0.get(0), DocumentStatus.COMPLETED);
        finishDocument(file0.get(1), DocumentStatus.COMPLETED);

        assertThat(batches.get(batch).status()).isEqualTo(BatchStatus.PROCESSING);
    }

    @Test
    void notLastDocumentDoesNothing() throws Exception {
        UUID batch = processingBatch(true, 1);
        List<UUID> docs = separate(files(batch).getFirst(), 2);

        try (PgNotifications notices = new PgNotifications(dataSource, "batch_completed")) {
            finishDocument(docs.getFirst(), DocumentStatus.COMPLETED);

            assertThat(notices.receive(500)).isEmpty();
        }
        assertThat(batches.get(batch).status()).isEqualTo(BatchStatus.PROCESSING);
        assertThat(webhookMessages(batch)).isZero();
    }

    @Test
    void failedFileWithOtherDocumentsCompletedIsPartiallyCompleted() {
        UUID batch = processingBatch(false, 2);
        List<UUID> files = files(batch);
        UUID doc = separate(files.get(0), 1).getFirst();
        sourceFiles.updateStatus(files.get(1), SourceFileStatus.SEPARATING, null);
        finishDocument(doc, DocumentStatus.COMPLETED);

        failFile(files.get(1));

        assertThat(batches.get(batch).status()).isEqualTo(BatchStatus.PARTIALLY_COMPLETED);
    }

    @Test
    void failedDocumentBesideCompletedOnesIsPartiallyCompleted() {
        UUID batch = processingBatch(false, 1);
        List<UUID> docs = separate(files(batch).getFirst(), 2);
        finishDocument(docs.get(0), DocumentStatus.FAILED);

        finishDocument(docs.get(1), DocumentStatus.COMPLETED);

        assertThat(batches.get(batch).status()).isEqualTo(BatchStatus.PARTIALLY_COMPLETED);
    }

    @Test
    void allFailedIsFailed() {
        UUID batch = processingBatch(false, 1);
        List<UUID> docs = separate(files(batch).getFirst(), 2);
        finishDocument(docs.get(0), DocumentStatus.FAILED);

        finishDocument(docs.get(1), DocumentStatus.FAILED);

        BatchRecord failed = batches.get(batch);
        assertThat(failed.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("No document completed");
    }

    @Test
    void everyFileFailedIsFailed() {
        UUID batch = processingBatch(false, 2);
        List<UUID> files = files(batch);
        failFile(files.get(0));

        failFile(files.get(1));

        BatchRecord failed = batches.get(batch);
        assertThat(failed.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("No document completed");
        assertThat(failed.completedAt()).isNotNull();
    }

    @Test
    void asyncBatchPublishesOneWebhook() throws Exception {
        UUID batch = processingBatch(true, 1);
        UUID doc = separate(files(batch).getFirst(), 1).getFirst();

        try (PgNotifications notices = new PgNotifications(dataSource, "batch_completed")) {
            finishDocument(doc, DocumentStatus.COMPLETED);

            assertThat(notices.receive(2000)).containsExactly(batch.toString());
        }
        assertThat(webhookMessages(batch)).isEqualTo(1);
        assertThat(batches.get(batch).webhookStatus()).isEqualTo(WebhookStatus.PENDING);
    }

    @Test
    void syncBatchPublishesNoWebhook() throws Exception {
        UUID batch = processingBatch(false, 1);
        WebhookStatus before = batches.get(batch).webhookStatus();
        UUID doc = separate(files(batch).getFirst(), 1).getFirst();

        try (PgNotifications notices = new PgNotifications(dataSource, "batch_completed")) {
            finishDocument(doc, DocumentStatus.COMPLETED);

            assertThat(notices.receive(2000)).containsExactly(batch.toString());
        }
        assertThat(webhookMessages(batch)).isZero();
        assertThat(batches.get(batch).webhookStatus()).isEqualTo(before);
    }

    @Test
    void rolledBackStepCompletesNothing() throws Exception {
        UUID batch = processingBatch(true, 1);
        UUID doc = separate(files(batch).getFirst(), 1).getFirst();

        try (PgNotifications notices = new PgNotifications(dataSource, "batch_completed")) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                documents.updateStatus(doc, DocumentStatus.COMPLETED, null);
                completion.onDocumentFinished(doc);
                status.setRollbackOnly();
            });

            assertThat(notices.receive(500)).isEmpty();
        }
        assertThat(batches.get(batch).status()).isEqualTo(BatchStatus.PROCESSING);
        assertThat(webhookMessages(batch)).isZero();
    }

    /** Both steps update their own document, then reach the check together; the batch lock lets one complete it. */
    @Test
    void lastDocumentsCompleteBatchOnce() throws Exception {
        UUID batch = processingBatch(true, 2);
        List<UUID> files = files(batch);
        UUID first = separate(files.get(0), 1).getFirst();
        UUID second = separate(files.get(1), 1).getFirst();
        CyclicBarrier bothUpdated = new CyclicBarrier(2);

        try (PgNotifications notices = new PgNotifications(dataSource, "batch_completed");
                ExecutorService steps = Executors.newFixedThreadPool(2)) {
            List<Future<?>> finished = new ArrayList<>();
            for (UUID doc : List.of(first, second)) {
                finished.add(steps.submit(() -> inTransaction(ignored -> {
                    documents.updateStatus(doc, DocumentStatus.COMPLETED, null);
                    await(bothUpdated);
                    completion.onDocumentFinished(doc);
                })));
            }
            for (Future<?> step : finished) {
                step.get(30, TimeUnit.SECONDS);
            }

            assertThat(notices.receive(2000)).containsExactly(batch.toString());
            assertThat(notices.receive(500)).isEmpty();
        }
        assertThat(batches.get(batch).status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(webhookMessages(batch)).isEqualTo(1);
    }

    /** A retried step, or one finishing after the batch was failed, finds it complete and leaves it be. */
    @Test
    void alreadyCompletedBatchIsLeftAlone() throws Exception {
        UUID batch = processingBatch(true, 1);
        UUID doc = separate(files(batch).getFirst(), 1).getFirst();
        finishDocument(doc, DocumentStatus.COMPLETED);
        BatchRecord completed = batches.get(batch);

        try (PgNotifications notices = new PgNotifications(dataSource, "batch_completed")) {
            inTransaction(ignored -> completion.onDocumentFinished(doc));

            assertThat(notices.receive(500)).isEmpty();
        }
        assertThat(batches.get(batch)).isEqualTo(completed);
        assertThat(webhookMessages(batch)).isEqualTo(1);
    }

    @Test
    void calledOutsideTransactionThrows() {
        UUID batch = processingBatch(true, 1);
        UUID file = files(batch).getFirst();
        UUID doc = separate(file, 1).getFirst();
        documents.updateStatus(doc, DocumentStatus.COMPLETED, null);

        assertThatThrownBy(() -> completion.onDocumentFinished(doc))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> completion.onFileFailed(file)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> completion.onBatchFailed(batch, "File could not be stored"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(batches.get(batch).status()).isEqualTo(BatchStatus.PROCESSING);
        assertThat(webhookMessages(batch)).isZero();
    }

    /** Intake (WI-11) fails a batch whose file can't be stored, before any step has started. */
    @Test
    void onBatchFailedSetsFailedAndNotifies() throws Exception {
        UUID batch = batches.create(new NewBatch("CAR_REFINANCE", true, "https://example.test/hook", "user-1", 1))
                .id();
        created.add(batch);

        try (PgNotifications notices = new PgNotifications(dataSource, "batch_completed")) {
            inTransaction(ignored -> completion.onBatchFailed(batch, "File could not be stored"));

            assertThat(notices.receive(2000)).containsExactly(batch.toString());
        }
        BatchRecord failed = batches.get(batch);
        assertThat(failed.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("File could not be stored");
        assertThat(failed.completedAt()).isNotNull();
        assertThat(failed.webhookStatus()).isEqualTo(WebhookStatus.PENDING);
        assertThat(webhookMessages(batch)).isEqualTo(1);
    }

    @Test
    void onBatchFailedLeavesCompletedBatchAlone() throws Exception {
        UUID batch = processingBatch(true, 1);
        finishDocument(separate(files(batch).getFirst(), 1).getFirst(), DocumentStatus.COMPLETED);
        BatchRecord completed = batches.get(batch);

        try (PgNotifications notices = new PgNotifications(dataSource, "batch_completed")) {
            inTransaction(ignored -> completion.onBatchFailed(batch, "File could not be stored"));

            assertThat(notices.receive(500)).isEmpty();
        }
        assertThat(batches.get(batch)).isEqualTo(completed);
        assertThat(webhookMessages(batch)).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void onBatchFailedNeedsAReason(String reason) {
        UUID batch = processingBatch(false, 1);

        assertThatThrownBy(() -> inTransaction(ignored -> completion.onBatchFailed(batch, reason)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(batches.get(batch).status()).isEqualTo(BatchStatus.PROCESSING);
    }

    /** A {@code PROCESSING} batch with {@code fileCount} two-page files, all {@code STORED}. */
    private UUID processingBatch(boolean async, int fileCount) {
        UUID batch = batches.create(new NewBatch(
                        "CAR_REFINANCE", async, async ? "https://example.test/hook" : null, "user-1", fileCount))
                .id();
        created.add(batch);
        List<NewSourceFile> files = new ArrayList<>();
        for (int i = 0; i < fileCount; i++) {
            files.add(new NewSourceFile("scan" + i + ".pdf", "application/pdf", 100, "a".repeat(64), 2));
        }
        sourceFiles
                .createAll(batch, files)
                .forEach(file -> sourceFiles.updateStatus(file.id(), SourceFileStatus.STORED, null));
        batches.updateStatus(batch, BatchStatus.PROCESSING, null);
        return batch;
    }

    private List<UUID> files(UUID batch) {
        return sourceFiles.findByBatch(batch).stream().map(file -> file.id()).toList();
    }

    /** Splits a two-page file into {@code count} (1 or 2) documents and marks it {@code SEPARATED}. */
    private List<UUID> separate(UUID file, int count) {
        List<PageRange> ranges =
                count == 1 ? List.of(new PageRange(1, 2)) : List.of(new PageRange(1, 1), new PageRange(2, 2));
        List<UUID> ids = documents.createAll(file, ranges).stream()
                .map(DocumentRecord::id)
                .toList();
        sourceFiles.updateStatus(file, SourceFileStatus.SEPARATED, null);
        return ids;
    }

    /** As an extraction step does: sets the document's status and runs the check in one transaction. */
    private void finishDocument(UUID document, DocumentStatus status) {
        inTransaction(ignored -> {
            documents.updateStatus(document, status, status == DocumentStatus.FAILED ? "extraction failed" : null);
            completion.onDocumentFinished(document);
        });
    }

    /** As a separation step does when it gives up: fails the file and runs the check in one transaction. */
    private void failFile(UUID file) {
        inTransaction(ignored -> {
            sourceFiles.updateStatus(file, SourceFileStatus.FAILED, "separation failed");
            completion.onFileFailed(file);
        });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("the other step never arrived", e);
        }
    }

    private void inTransaction(Consumer<Object> work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(work::accept);
    }

    private int webhookMessages(UUID batch) {
        return jdbc.sql("SELECT count(*) FROM document_reader.outbox_message "
                        + "WHERE aggregate_id = ? AND message_type = 'DELIVER_WEBHOOK'")
                .param(batch)
                .query(Integer.class)
                .single();
    }
}
