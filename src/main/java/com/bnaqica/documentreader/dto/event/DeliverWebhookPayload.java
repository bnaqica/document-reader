package com.bnaqica.documentreader.dto.event;

import java.util.UUID;

/** The {@code DELIVER_WEBHOOK} message: which batch's result to deliver. The result is read when it is delivered. */
public record DeliverWebhookPayload(UUID batchId) {}
