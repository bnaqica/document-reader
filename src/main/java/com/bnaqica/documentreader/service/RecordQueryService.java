package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.PageRecord;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import java.util.List;
import java.util.UUID;

/**
 * Read access to the processing records for the caller who sent the batch, and for admins. A record of another
 * caller's batch throws the same {@code ResourceNotFoundException} as a missing one, so its existence isn't revealed.
 */
public interface RecordQueryService {

    /** Newest first; only the caller's own batches unless the caller is an admin. */
    List<BatchRecord> batches(Caller caller, int limit);

    BatchRecord batch(UUID batchId, Caller caller);

    List<SourceFileRecord> sourceFiles(UUID batchId, Caller caller);

    List<DocumentRecord> documents(UUID batchId, Caller caller);

    SourceFileRecord sourceFile(UUID sourceFileId, Caller caller);

    List<PageRecord> pages(UUID sourceFileId, Caller caller);

    DocumentRecord document(UUID documentId, Caller caller);
}
