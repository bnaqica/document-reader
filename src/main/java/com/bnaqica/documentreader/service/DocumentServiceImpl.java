package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.Document;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.exception.InvalidPageRangesException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.repository.DocumentRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Depends on {@link SourceFileService} (a file's batch and page count) and {@link DocumentTypeService} (that a type
 * exists); neither depends back on this.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentServiceImpl implements DocumentService {

    private static final String RESOURCE = "Document";

    private final DocumentRepository repository;
    private final SourceFileService sourceFiles;
    private final DocumentTypeService documentTypes;
    private final Clock clock;

    @Override
    @Transactional
    public List<DocumentRecord> createAll(UUID sourceFileId, List<PageRange> ranges) {
        SourceFileRecord file = sourceFiles.get(sourceFileId);
        UUID batchId = file.batchId();
        List<PageRange> ordered = ranges.stream()
                .sorted(Comparator.comparingInt(PageRange::start))
                .toList();
        requireValid(ordered, file.pageCount());
        List<Document> created = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            created.add(Document.builder()
                    .id(UUID.randomUUID())
                    .batchId(batchId)
                    .sourceFileId(sourceFileId)
                    .sequence(i + 1)
                    .startPage(ordered.get(i).start())
                    .endPage(ordered.get(i).end())
                    .status(DocumentStatus.PENDING)
                    .build());
        }
        repository.saveAll(created);
        log.info("Created {} documents for source file {}", created.size(), sourceFileId);
        return created.stream().map(DocumentServiceImpl::toRecord).toList();
    }

    @Override
    public DocumentRecord get(UUID id) {
        return toRecord(find(id));
    }

    @Override
    public List<DocumentRecord> findByBatch(UUID batchId) {
        return repository.findByBatchInFileOrder(batchId).stream()
                .map(DocumentServiceImpl::toRecord)
                .toList();
    }

    @Override
    public List<DocumentRecord> findBySourceFile(UUID sourceFileId) {
        return repository.findBySourceFileIdOrderBySequence(sourceFileId).stream()
                .map(DocumentServiceImpl::toRecord)
                .toList();
    }

    @Override
    @Transactional
    public void updateStatus(UUID id, DocumentStatus status, String failureReason) {
        // the clock is read only when the time is recorded: on finishing
        find(id).updateStatus(status, failureReason, status.isFinished() ? clock.instant() : null);
        log.info("Document {} is now {}", id, status);
    }

    @Override
    @Transactional
    public void assignType(UUID id, UUID documentTypeId) {
        Document document = find(id);
        documentTypes.get(documentTypeId);
        document.assignType(documentTypeId);
        log.info("Document {} classified as type {}", id, documentTypeId);
    }

    /** Ranges sorted by start: each within 1..pageCount, not reversed, and starting after the previous one ends. */
    private static void requireValid(List<PageRange> ranges, int pageCount) {
        if (ranges.isEmpty()) {
            throw new InvalidPageRangesException("A file has at least one document, so at least one page range");
        }
        int previousEnd = 0;
        for (PageRange range : ranges) {
            if (range.start() < 1 || range.end() > pageCount || range.start() > range.end()) {
                throw new InvalidPageRangesException(
                        "Pages " + range.start() + "-" + range.end() + " are not within 1-" + pageCount);
            }
            if (range.start() <= previousEnd) {
                throw new InvalidPageRangesException("Pages " + range.start() + "-" + range.end()
                        + " overlap the document ending at " + previousEnd);
            }
            previousEnd = range.end();
        }
    }

    private Document find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private static DocumentRecord toRecord(Document document) {
        return DocumentRecord.builder()
                .id(document.getId())
                .batchId(document.getBatchId())
                .sourceFileId(document.getSourceFileId())
                .sequence(document.getSequence())
                .startPage(document.getStartPage())
                .endPage(document.getEndPage())
                .status(document.getStatus())
                .documentTypeId(document.getDocumentTypeId())
                .truncated(document.isTruncated())
                .failureReason(document.getFailureReason())
                .completedAt(document.getCompletedAt())
                .build();
    }
}
