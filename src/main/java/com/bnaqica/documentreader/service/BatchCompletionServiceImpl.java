package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.event.DeliverWebhookPayload;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import com.bnaqica.documentreader.entity.WebhookStatus;
import com.bnaqica.documentreader.publisher.OutboxPublisher;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class BatchCompletionServiceImpl implements BatchCompletionService {

    static final String NO_DOCUMENT_COMPLETED = "No document completed";

    private final BatchService batches;
    private final SourceFileService sourceFiles;
    private final DocumentService documents;
    private final CompletionWaitService completionWait;
    private final OutboxPublisher outbox;
    private final Clock clock;

    @Override
    public void onDocumentFinished(UUID documentId) {
        check(documents.get(documentId).batchId());
    }

    @Override
    public void onFileFailed(UUID sourceFileId) {
        check(sourceFiles.get(sourceFileId).batchId());
    }

    @Override
    public void onBatchFailed(UUID batchId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A failed batch needs a reason");
        }
        BatchRecord batch = batches.lockForUpdate(batchId);
        if (batch.status().isFinal()) {
            log.debug("Batch {} is already {}; not failing it", batchId, batch.status());
            return;
        }
        finish(batch, BatchStatus.FAILED, reason);
    }

    /** Completes the batch if its last file and document have finished; the lock makes only one caller do it. */
    private void check(UUID batchId) {
        BatchRecord batch = batches.lockForUpdate(batchId);
        if (batch.status().isFinal()) {
            log.debug("Batch {} is already {}; nothing to check", batchId, batch.status());
            return;
        }
        List<SourceFileRecord> files = sourceFiles.findByBatch(batchId);
        List<DocumentRecord> docs = documents.findByBatch(batchId);
        boolean filesDone = files.stream()
                .allMatch(file ->
                        file.status() == SourceFileStatus.SEPARATED || file.status() == SourceFileStatus.FAILED);
        boolean docsDone = docs.stream().allMatch(doc -> doc.status().isFinished());
        if (!filesDone || !docsDone) {
            log.debug("Batch {} is still running: {} files, {} documents", batchId, files.size(), docs.size());
            return;
        }
        BatchStatus status = finalStatus(files, docs);
        // BatchService.complete logs the completion itself
        finish(batch, status, status == BatchStatus.FAILED ? NO_DOCUMENT_COMPLETED : null);
        log.debug("Batch {} had {} files and {} documents", batchId, files.size(), docs.size());
    }

    /** Records the final status, announces it on commit and, for an async batch, queues its one webhook. */
    private void finish(BatchRecord batch, BatchStatus status, String reason) {
        batches.complete(batch.id(), status, reason, clock.instant());
        completionWait.notifyBatchCompleted(batch.id());
        if (batch.async()) {
            batches.updateWebhookStatus(batch.id(), WebhookStatus.PENDING);
            outbox.publish(MessageType.DELIVER_WEBHOOK, batch.id(), new DeliverWebhookPayload(batch.id()));
        }
    }

    /** §7.3: nothing completed is FAILED (even with no documents); everything separated and completed is COMPLETED. */
    private static BatchStatus finalStatus(List<SourceFileRecord> files, List<DocumentRecord> docs) {
        if (docs.stream().noneMatch(doc -> doc.status() == DocumentStatus.COMPLETED)) {
            return BatchStatus.FAILED;
        }
        boolean allSeparated = files.stream().allMatch(file -> file.status() == SourceFileStatus.SEPARATED);
        boolean allCompleted = docs.stream().allMatch(doc -> doc.status() == DocumentStatus.COMPLETED);
        return allSeparated && allCompleted ? BatchStatus.COMPLETED : BatchStatus.PARTIALLY_COMPLETED;
    }
}
