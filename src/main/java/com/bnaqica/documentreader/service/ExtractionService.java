package com.bnaqica.documentreader.service;

import java.util.UUID;

/**
 * Process 3 (§12): reads one classified document's data and summary in three stages (first passes, a consolidated
 * draft, a final determination checked against the type's schema), completes the document and runs the batch
 * completion check. It is also the {@code EXTRACT_DOCUMENT} inbox handler.
 */
public interface ExtractionService {

    /**
     * Runs with no transaction open, because it calls OpenAI. Does nothing for a document that is already completed,
     * failed or extracted, so a retry or a second delivery is safe.
     *
     * @throws IllegalStateException when the final data fails validation twice, so the inbox retries
     */
    void extract(UUID documentId);
}
