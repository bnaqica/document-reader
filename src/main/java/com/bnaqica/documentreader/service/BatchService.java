package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.WebhookStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** CRUD for batches. Only the pipeline changes them. */
public interface BatchService {

    /** Starts {@code RECEIVED}; throws {@code ResourceNotFoundException} for an unknown loan purpose. */
    BatchRecord create(NewBatch batch);

    /** Throws {@code ResourceNotFoundException} for an unknown id. */
    BatchRecord get(UUID id);

    /**
     * Locks the batch for the rest of the caller's transaction and returns it as it is now. Make it the first read of
     * the batch in that transaction: a batch already loaded earlier in it would come back as it was then. A second caller waits
     * until the first transaction ends. Throws {@code IllegalTransactionStateException} outside a transaction.
     */
    BatchRecord lockForUpdate(UUID id);

    /**
     * Moves the batch between statuses that aren't final. Throws {@code IllegalArgumentException} for a final status
     * (use {@link #complete}) and {@code IllegalStateException} for a completed batch.
     */
    void updateStatus(UUID id, BatchStatus status, String failureReason);

    /**
     * Records the final status, reason (kept only with {@code FAILED}) and time. Throws
     * {@code IllegalArgumentException} for a status that isn't final and {@code IllegalStateException} if the batch is
     * already complete.
     */
    void complete(UUID id, BatchStatus finalStatus, String failureReason, Instant completedAt);

    void updateWebhookStatus(UUID id, WebhookStatus status);

    /** Newest first; {@code requestedBy} null means every caller's batches. */
    List<BatchRecord> findRecent(String requestedBy, int limit);
}
