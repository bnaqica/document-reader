package com.bnaqica.documentreader.service;

import java.util.UUID;

/**
 * Process 2 (§11): decides one document's type by voting over classification runs, with a tie-break round and a
 * confidence fallback, then queues extraction. It is also the {@code CLASSIFY_DOCUMENT} inbox handler.
 */
public interface ClassificationService {

    /**
     * Runs with no transaction open, because it calls OpenAI. Does nothing for a document that is already classified,
     * past classification or failed, so a retry or a second delivery is safe.
     *
     * @throws IllegalStateException when too few classification runs succeed, so the inbox retries
     */
    void classify(UUID documentId);
}
