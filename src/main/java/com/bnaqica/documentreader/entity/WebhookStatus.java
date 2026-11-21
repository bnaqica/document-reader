package com.bnaqica.documentreader.entity;

/** Whether an async batch's webhook is still owed, delivered or given up on. Sync batches have none. */
public enum WebhookStatus {
    NOT_APPLICABLE,
    PENDING,
    DELIVERED,
    FAILED
}
