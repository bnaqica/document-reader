package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.request.BatchUploadRequest;
import com.bnaqica.documentreader.dto.response.BatchResultResponse;
import com.bnaqica.documentreader.entity.BatchStatus;
import java.util.UUID;

/** Validates a whole batch, stores it, queues its files for separation and, in wait mode, waits for its result. */
public interface BatchIntakeService {

    /**
     * Runs outside any transaction, because a sync batch waits for its result.
     *
     * @throws com.bnaqica.documentreader.exception.BatchValidationException when the request or a file is refused;
     *     nothing is stored then
     * @throws com.bnaqica.documentreader.exception.InvalidWebhookUrlException for a webhook URL that isn't absolute
     *     http or https
     */
    IntakeOutcome submit(BatchUploadRequest request, Caller caller);

    /** How intake answers: with the result (200), or with the batch still running (202). */
    sealed interface IntakeOutcome {}

    /** The batch reached a final status before the answer. */
    record Finished(BatchResultResponse result) implements IntakeOutcome {}

    /** An async batch, or a sync batch still running when the wait timed out. */
    record Accepted(UUID batchId, BatchStatus status) implements IntakeOutcome {}
}
