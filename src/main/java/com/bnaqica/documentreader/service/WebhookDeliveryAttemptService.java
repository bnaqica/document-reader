package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.response.WebhookAttemptRecord;
import java.util.List;
import java.util.UUID;

/** Webhook delivery attempts of a batch. An attempt number is used once; a duplicate is refused by the database. */
public interface WebhookDeliveryAttemptService {

    /** {@code httpStatus} is null when no response came; {@code error} is null on success. */
    WebhookAttemptRecord record(UUID batchId, int attempt, Integer httpStatus, String error);

    /** In attempt order. */
    List<WebhookAttemptRecord> findByBatch(UUID batchId);
}
