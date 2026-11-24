package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.entity.DocumentStatus;
import java.util.List;
import java.util.UUID;

/** CRUD for the documents found in source files. */
public interface DocumentService {

    /**
     * Creates one {@code PENDING} document per range, numbered 1, 2, 3… in page order, in the file's own batch.
     * Throws {@code InvalidPageRangesException}, creating nothing, if there are no ranges or one is outside the file,
     * reversed, or overlaps another. Call it once per file: check {@link #findBySourceFile} first when retrying,
     * because a second call fails on the sequence numbers.
     */
    List<DocumentRecord> createAll(UUID sourceFileId, List<PageRange> ranges);

    /** Throws {@code ResourceNotFoundException} for an unknown id. */
    DocumentRecord get(UUID id);

    /**
     * Locks the document for the rest of the caller's transaction and returns it as it is now, so a second locker
     * waits until that transaction ends. Make it the first read of the document in the transaction.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException outside a transaction
     */
    DocumentRecord lockForUpdate(UUID id);

    /** By file index, then sequence. */
    List<DocumentRecord> findByBatch(UUID batchId);

    /** By sequence. */
    List<DocumentRecord> findBySourceFile(UUID sourceFileId);

    /** The failure reason is kept only with {@code FAILED}; completed or failed records the time. */
    void updateStatus(UUID id, DocumentStatus status, String failureReason);

    /** Throws {@code ResourceNotFoundException} for an unknown document or document type. */
    void assignType(UUID id, UUID documentTypeId);

    /** Records that extraction read only the document's first {@code extraction.max-pages} pages. */
    void markTruncated(UUID id);
}
