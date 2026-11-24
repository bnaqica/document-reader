package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.dto.CallContext;
import com.bnaqica.documentreader.dto.ConsolidatedAnswer;
import com.bnaqica.documentreader.dto.ExtractionAnswer;
import com.bnaqica.documentreader.dto.NewExtractionResult;
import com.bnaqica.documentreader.dto.NewExtractionRun;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import com.bnaqica.documentreader.dto.response.ExtractionRunRecord;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.entity.ExtractionStage;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.processors.DataSchemaValidator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

/**
 * Process 3 (§12), and the {@code EXTRACT_DOCUMENT} inbox handler. First passes over the pages, a text-only
 * consolidated draft that lists where they disagreed, and a final determination over the pages again, checked
 * against the type's schema with one corrective retry. Then, in one transaction, the result, the document's
 * completion and the batch completion check. Every model call runs outside a transaction; the passes are saved as
 * they finish, so a retry repeats only what is missing. Extracted values, summaries and validation messages are
 * never logged (§18).
 */
@Slf4j
@Service
public class ExtractionServiceImpl implements ExtractionService, InboxMessageHandler {

    static final String EXTRACTION_FAILED = "Extraction failed";

    private final DocumentService documents;
    private final SourceFilePageService pages;
    private final BatchService batches;
    private final LoanPurposeService loanPurposes;
    private final DocumentTypeService documentTypes;
    private final ExtractionRunService runs;
    private final ExtractionResultService results;
    private final InboxMessageService inbox;
    private final BatchCompletionService completion;
    private final VisionClient vision;
    private final DataSchemaValidator validator;
    private final DocumentReaderProperties.Extraction settings;
    private final TransactionTemplate transaction;

    public ExtractionServiceImpl(
            DocumentService documents,
            SourceFilePageService pages,
            BatchService batches,
            LoanPurposeService loanPurposes,
            DocumentTypeService documentTypes,
            ExtractionRunService runs,
            ExtractionResultService results,
            InboxMessageService inbox,
            BatchCompletionService completion,
            VisionClient vision,
            DataSchemaValidator validator,
            DocumentReaderProperties properties,
            PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.pages = pages;
        this.batches = batches;
        this.loanPurposes = loanPurposes;
        this.documentTypes = documentTypes;
        this.runs = runs;
        this.results = results;
        this.inbox = inbox;
        this.completion = completion;
        this.vision = vision;
        this.validator = validator;
        this.settings = properties.extraction();
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public MessageType type() {
        return MessageType.EXTRACT_DOCUMENT;
    }

    @Override
    public void handle(InboxMessageRecord message) {
        extract(message.aggregateId(), message.id());
    }

    @Override
    public void extract(UUID documentId) {
        extract(documentId, null);
    }

    /**
     * Runs in the inbox's {@code FAILED} transaction. The document is locked first, as the final transaction locks
     * it, so of a failure and a late attempt's result only the first to commit counts.
     */
    @Override
    public void onExhausted(InboxMessageRecord message, String lastError) {
        UUID documentId = message.aggregateId();
        DocumentStatus now = documents.lockForUpdate(documentId).status();
        if (now.isFinished()) {
            log.debug("Document {} is already {}; not failing it", documentId, now);
            return;
        }
        // The inbox's last error isn't copied: callers see this reason in the batch result
        documents.updateStatus(documentId, DocumentStatus.FAILED, EXTRACTION_FAILED);
        completion.onDocumentFinished(documentId);
    }

    private void extract(UUID documentId, UUID messageId) {
        DocumentRecord document = documents.get(documentId);
        if (document.status().isFinished() || results.find(documentId).isPresent()) {
            log.debug("Document {} is already {}; nothing to extract", documentId, document.status());
            return;
        }
        if (document.status() != DocumentStatus.EXTRACTING && !startExtracting(documentId)) {
            return;
        }
        if (document.documentTypeId() == null) {
            throw new IllegalStateException("Document " + documentId + " has no type to extract");
        }
        DocumentTypeResponse type = documentTypes.get(document.documentTypeId());
        JsonNode schema = type.dataSchema();
        int pageCount = document.endPage() - document.startPage() + 1;
        boolean truncated = pageCount > settings.maxPages();
        List<PageImageRef> sent = pagesOf(document);
        CallContext context = callContext(document);

        List<ExtractionAnswer> passes = passes(documentId, context, sent, schema);
        ConsolidatedAnswer draft = vision.consolidate(context, schema, passes);
        save(documentId, ExtractionStage.CONSOLIDATED, draft.data(), draft.summary());
        ExtractionAnswer last = finalAnswer(documentId, context, sent, schema, draft);
        save(documentId, ExtractionStage.FINAL, last.data(), last.summary());
        finish(documentId, messageId, type, last, truncated);
    }

    /**
     * {@code CLASSIFIED} to {@code EXTRACTING} under the document's lock, so an attempt that read the document before
     * its message was exhausted can't overwrite the failure. False when the document has moved on meanwhile.
     */
    private boolean startExtracting(UUID documentId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            DocumentStatus now = documents.lockForUpdate(documentId).status();
            if (now == DocumentStatus.CLASSIFIED) {
                documents.updateStatus(documentId, DocumentStatus.EXTRACTING, null);
                return true;
            }
            log.debug("Document {} is {}; not extracting it", documentId, now);
            return now == DocumentStatus.EXTRACTING;
        }));
    }

    /**
     * The first {@code extraction.passes} saved passes, after making the calls still missing at once. A call that
     * fails is rethrown once the others have finished and been saved, so the inbox retries only what's missing.
     */
    private List<ExtractionAnswer> passes(
            UUID documentId, CallContext context, List<PageImageRef> sent, JsonNode schema) {
        List<ExtractionRunRecord> saved = runs.findByDocumentAndStage(documentId, ExtractionStage.INITIAL);
        int needed = Math.max(0, settings.passes() - saved.size());
        if (needed > 0) {
            int nextPass = nextPass(saved);
            try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> calls = new ArrayList<>();
                for (int pass = nextPass; pass < nextPass + needed; pass++) {
                    int passNumber = pass;
                    calls.add(threads.submit(() -> {
                        ExtractionAnswer answer = vision.extract(context, sent, schema);
                        runs.save(
                                documentId,
                                new NewExtractionRun(
                                        ExtractionStage.INITIAL, passNumber, dataOf(answer.data()), answer.summary()));
                    }));
                }
                RuntimeException first = null;
                for (Future<?> call : calls) {
                    try {
                        waitFor(call);
                    } catch (RuntimeException e) {
                        if (first == null) {
                            first = e;
                        } else if (e != first) {
                            first.addSuppressed(e); // kept on the exception, not dropped
                        }
                    }
                }
                if (first != null) {
                    log.info("An extraction pass of document {} failed; the saved passes are kept", documentId);
                    throw first;
                }
            }
            saved = runs.findByDocumentAndStage(documentId, ExtractionStage.INITIAL);
        }
        log.info("Document {} has {} extraction passes", documentId, Math.min(saved.size(), settings.passes()));
        return saved.stream()
                .sorted(Comparator.comparingInt(ExtractionRunRecord::passNumber))
                .limit(settings.passes())
                .map(run -> new ExtractionAnswer(run.data(), run.summary()))
                .toList();
    }

    /** The final answer, with one corrective retry carrying the validation errors back to the model. */
    private ExtractionAnswer finalAnswer(
            UUID documentId, CallContext context, List<PageImageRef> sent, JsonNode schema, ConsolidatedAnswer draft) {
        ExtractionAnswer answer = vision.finalizeExtraction(context, sent, schema, draft, List.of());
        List<String> errors = validator.validate(schema, answer.data());
        if (errors.isEmpty()) {
            return answer;
        }
        // Only the count is logged: the messages can quote the extracted values
        log.warn("Final data of document {} broke its schema in {} places; asking again", documentId, errors.size());
        answer = vision.finalizeExtraction(context, sent, schema, draft, errors);
        errors = validator.validate(schema, answer.data());
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Final extraction data of document " + documentId
                    + " failed validation twice, in " + errors.size() + " places");
        }
        return answer;
    }

    /**
     * The result, the document's completion, the batch completion check and the inbox message's done mark, together.
     * The document is locked and checked again first: another attempt may have completed or failed it meanwhile.
     */
    private void finish(
            UUID documentId, UUID messageId, DocumentTypeResponse type, ExtractionAnswer last, boolean truncated) {
        boolean completed = Boolean.TRUE.equals(transaction.execute(status -> {
            DocumentStatus now = documents.lockForUpdate(documentId).status();
            if (now != DocumentStatus.EXTRACTING) {
                log.info("Document {} became {} while it was being extracted; keeping that", documentId, now);
                return false;
            }
            results.save(
                    documentId, new NewExtractionResult(type.id(), type.schemaVersion(), last.data(), last.summary()));
            if (truncated) {
                documents.markTruncated(documentId);
            }
            documents.updateStatus(documentId, DocumentStatus.COMPLETED, null);
            completion.onDocumentFinished(documentId);
            if (messageId != null) {
                inbox.markDone(messageId);
            }
            return true;
        }));
        if (completed) {
            log.info("Extracted document {}", documentId);
        }
    }

    private void save(UUID documentId, ExtractionStage stage, JsonNode data, String summary) {
        int pass = nextPass(runs.findByDocumentAndStage(documentId, stage));
        runs.save(documentId, new NewExtractionRun(stage, pass, dataOf(data), summary));
    }

    private static int nextPass(List<ExtractionRunRecord> saved) {
        return saved.stream().mapToInt(ExtractionRunRecord::passNumber).max().orElse(0) + 1;
    }

    /** The run table needs data; a missing answer is saved as JSON null, which a later stage can still read. */
    private static JsonNode dataOf(JsonNode data) {
        return data == null ? NullNode.getInstance() : data;
    }

    /** The document's first {@code extraction.max-pages} pages, from its file's recorded pages. */
    private List<PageImageRef> pagesOf(DocumentRecord document) {
        int last = Math.min(document.endPage(), document.startPage() + settings.maxPages() - 1);
        return pages.findBySourceFile(document.sourceFileId()).stream()
                .filter(page -> page.pageNumber() >= document.startPage() && page.pageNumber() <= last)
                .map(page -> new PageImageRef(page.pageNumber(), page.s3Key()))
                .toList();
    }

    private CallContext callContext(DocumentRecord document) {
        BatchRecord batch = batches.get(document.batchId());
        return CallContext.builder()
                .batchId(batch.id())
                .sourceFileId(document.sourceFileId())
                .documentId(document.id())
                .loanPurposeDescription(
                        loanPurposes.get(batch.loanPurposeCode()).description())
                .build();
    }

    private static void waitFor(Future<?> call) {
        try {
            call.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("An extraction call failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while extracting", e);
        }
    }
}
