package com.bnaqica.documentreader.service;

import java.time.Duration;
import java.util.UUID;

/**
 * Lets a caller wait for its batch to reach a final status, woken by a {@code batch_completed} notification from any
 * instance, with a status re-read every {@code wait.poll-interval} in case a notification is missed.
 */
public interface CompletionWaitService {

    /**
     * True as soon as the batch is final; false when {@code timeout} passes first, or when the waiting thread is
     * interrupted (the interrupt is kept). A notification only prompts a re-read: the status decides. Must be called
     * outside any transaction, so the batch is committed and each re-read sees the latest status.
     *
     * @throws IllegalStateException inside a transaction
     * @throws com.bnaqica.documentreader.exception.ResourceNotFoundException for an unknown batch
     */
    boolean await(UUID batchId, Duration timeout);

    /**
     * Announces the batch's completion from inside the completing transaction: every instance's waiters hear of it
     * when it commits, and never for a rollback.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException outside a transaction
     */
    void notifyBatchCompleted(UUID batchId);

    /** Wakes whoever waits on this batch, if anyone does. */
    void onBatchCompleted(UUID batchId);

    /** How many batches are being waited on, on this instance. */
    int waitingCount();
}
