package com.bnaqica.documentreader.service;

import java.util.UUID;

/**
 * Process 1 (§9): splits one stored file into documents by voting over separation runs, then queues each document
 * for classification. It is also the {@code SEPARATE_FILE} inbox handler.
 */
public interface SeparationService {

    /**
     * Runs with no transaction open, because it calls OpenAI. Does nothing for a file that is already separated or
     * failed, so a retry or a second delivery is safe.
     *
     * @throws IllegalStateException when too few separation runs of a window succeed, so the inbox retries
     */
    void separate(UUID sourceFileId);
}
