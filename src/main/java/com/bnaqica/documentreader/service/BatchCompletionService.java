package com.bnaqica.documentreader.service;

import java.util.UUID;

/**
 * Completes a batch exactly once, when its last file and document finish. Every method runs inside the caller's
 * transaction, so the completion, the {@code batch_completed} notice and the webhook message commit or roll back with
 * the caller's own changes. Callers set the document's or file's status first; nothing here changes a file or
 * document.
 *
 * <p>Before calling, a caller must not have loaded the batch or any other file or document of it in the same
 * transaction: the check would then read those as they were when loaded, not as other steps have since committed
 * them, and could miss the completion.
 */
public interface BatchCompletionService {

    /**
     * Runs the completion check for the batch of a document that has just completed or failed.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException outside a transaction
     */
    void onDocumentFinished(UUID documentId);

    /**
     * Runs the completion check for the batch of a file that has just failed.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException outside a transaction
     */
    void onFileFailed(UUID sourceFileId);

    /**
     * Completes the batch as {@code FAILED} with the reason, whatever its files and documents are doing. Does nothing
     * for a batch that is already complete.
     *
     * @throws IllegalArgumentException for a blank reason
     * @throws org.springframework.transaction.IllegalTransactionStateException outside a transaction
     */
    void onBatchFailed(UUID batchId, String reason);
}
