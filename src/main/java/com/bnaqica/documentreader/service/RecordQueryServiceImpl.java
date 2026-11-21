package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.PageRecord;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** An action service over the four record CRUD services; ownership is checked here, on the batch. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecordQueryServiceImpl implements RecordQueryService {

    private final BatchService batches;
    private final SourceFileService sourceFiles;
    private final SourceFilePageService pages;
    private final DocumentService documents;

    @Override
    public List<BatchRecord> batches(Caller caller, int limit) {
        return batches.findRecent(caller.admin() ? null : caller.subject(), limit);
    }

    @Override
    public BatchRecord batch(UUID batchId, Caller caller) {
        return readableBatch(batchId, caller, "Batch", batchId);
    }

    @Override
    public List<SourceFileRecord> sourceFiles(UUID batchId, Caller caller) {
        readableBatch(batchId, caller, "Batch", batchId);
        return sourceFiles.findByBatch(batchId);
    }

    @Override
    public List<DocumentRecord> documents(UUID batchId, Caller caller) {
        readableBatch(batchId, caller, "Batch", batchId);
        return documents.findByBatch(batchId);
    }

    @Override
    public SourceFileRecord sourceFile(UUID sourceFileId, Caller caller) {
        SourceFileRecord file = sourceFiles.get(sourceFileId);
        readableBatch(file.batchId(), caller, "Source file", sourceFileId);
        return file;
    }

    @Override
    public List<PageRecord> pages(UUID sourceFileId, Caller caller) {
        sourceFile(sourceFileId, caller);
        return pages.findBySourceFile(sourceFileId);
    }

    @Override
    public DocumentRecord document(UUID documentId, Caller caller) {
        DocumentRecord document = documents.get(documentId);
        readableBatch(document.batchId(), caller, "Document", documentId);
        return document;
    }

    /** Not found when the caller may not read it, reported as the record they asked for. */
    private BatchRecord readableBatch(UUID batchId, Caller caller, String resource, UUID requestedId) {
        BatchRecord batch = batches.get(batchId);
        if (!caller.mayRead(batch.requestedBy())) {
            throw new ResourceNotFoundException(resource, requestedId);
        }
        return batch;
    }
}
