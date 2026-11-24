package com.bnaqica.documentreader.service;

import java.util.UUID;

/**
 * Process 4 (§15): posts an async batch's result to its webhook URL, once per batch, recording every attempt. It is
 * also the {@code DELIVER_WEBHOOK} inbox handler, whose backoff retries a failed attempt.
 */
public interface WebhookDeliveryService {

    /**
     * Runs with no transaction open, because it calls the receiver. Does nothing for a batch whose webhook isn't
     * pending, so a retry or a second delivery is safe. {@code deliveryId} is sent for the receiver's de-duplication.
     *
     * @throws IllegalStateException when the attempt fails, so the inbox retries it
     */
    void deliver(UUID batchId, UUID deliveryId);
}
