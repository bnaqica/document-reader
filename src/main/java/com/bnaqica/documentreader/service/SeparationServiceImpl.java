package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.client.DocumentStorageClient;
import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.dto.CallContext;
import com.bnaqica.documentreader.dto.CatalogEntry;
import com.bnaqica.documentreader.dto.NewSeparationRun;
import com.bnaqica.documentreader.dto.PageImage;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.dto.SeparationAnswer;
import com.bnaqica.documentreader.dto.SeparationBoundary;
import com.bnaqica.documentreader.dto.event.ClassifyDocumentPayload;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.dto.response.PageRecord;
import com.bnaqica.documentreader.dto.response.SeparationRunRecord;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import com.bnaqica.documentreader.exception.VisionException;
import com.bnaqica.documentreader.processors.BoundaryVoteProcessor;
import com.bnaqica.documentreader.processors.BoundaryVoteProcessor.WindowRuns;
import com.bnaqica.documentreader.processors.DetectedType;
import com.bnaqica.documentreader.processors.ImageConversionProcessor;
import com.bnaqica.documentreader.processors.S3Keys;
import com.bnaqica.documentreader.publisher.OutboxPublisher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Process 1 (§9), and the {@code SEPARATE_FILE} inbox handler. It converts the file's pages once, asks for document
 * starts in overlapping windows with several runs each, votes on every page gap, and then, in one transaction, creates
 * the documents, marks the file separated and queues classification. Every model call runs outside a transaction, and
 * every run is saved as it finishes, so a retry repeats only what is missing.
 */
@Slf4j
@Service
public class SeparationServiceImpl implements SeparationService, InboxMessageHandler {

    static final String SEPARATION_FAILED = "Separation failed";

    private final SourceFileService sourceFiles;
    private final SourceFilePageService pages;
    private final SeparationRunService separationRuns;
    private final DocumentService documents;
    private final BatchService batches;
    private final LoanPurposeService loanPurposes;
    private final DocumentTypeService documentTypes;
    private final InboxMessageService inbox;
    private final BatchCompletionService completion;
    private final OutboxPublisher outbox;
    private final DocumentStorageClient storage;
    private final S3Keys keys;
    private final ImageConversionProcessor conversion;
    private final VisionClient vision;
    private final BoundaryVoteProcessor votes;
    private final DocumentReaderProperties.Separation settings;
    private final TransactionTemplate transaction;

    public SeparationServiceImpl(
            SourceFileService sourceFiles,
            SourceFilePageService pages,
            SeparationRunService separationRuns,
            DocumentService documents,
            BatchService batches,
            LoanPurposeService loanPurposes,
            DocumentTypeService documentTypes,
            InboxMessageService inbox,
            BatchCompletionService completion,
            OutboxPublisher outbox,
            DocumentStorageClient storage,
            S3Keys keys,
            ImageConversionProcessor conversion,
            VisionClient vision,
            BoundaryVoteProcessor votes,
            DocumentReaderProperties properties,
            PlatformTransactionManager transactionManager) {
        this.sourceFiles = sourceFiles;
        this.pages = pages;
        this.separationRuns = separationRuns;
        this.documents = documents;
        this.batches = batches;
        this.loanPurposes = loanPurposes;
        this.documentTypes = documentTypes;
        this.inbox = inbox;
        this.completion = completion;
        this.outbox = outbox;
        this.storage = storage;
        this.keys = keys;
        this.conversion = conversion;
        this.vision = vision;
        this.votes = votes;
        this.settings = properties.separation();
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public MessageType type() {
        return MessageType.SEPARATE_FILE;
    }

    @Override
    public void handle(InboxMessageRecord message) {
        separate(message.aggregateId(), message.id());
    }

    @Override
    public void separate(UUID sourceFileId) {
        separate(sourceFileId, null);
    }

    /**
     * Runs in the inbox's {@code FAILED} transaction. The file is locked first, as {@link #finish} locks it, so of a
     * failure and a late attempt's documents only the first to commit counts. A file already separated is left alone;
     * otherwise it fails, and the batch completion check runs, so the batch never waits for it.
     */
    @Override
    public void onExhausted(InboxMessageRecord message, String lastError) {
        UUID fileId = message.aggregateId();
        if (sourceFiles.lockForUpdate(fileId).status() == SourceFileStatus.SEPARATED) {
            log.debug("Source file {} is already separated; not failing it", fileId);
            return;
        }
        // The inbox's last error isn't copied: callers see this reason in the batch result
        sourceFiles.updateStatus(fileId, SourceFileStatus.FAILED, SEPARATION_FAILED);
        completion.onFileFailed(fileId);
    }

    private void separate(UUID fileId, UUID messageId) {
        SourceFileRecord file = sourceFiles.get(fileId);
        if (file.status() == SourceFileStatus.SEPARATED || file.status() == SourceFileStatus.FAILED) {
            log.debug("Source file {} is already {}; nothing to separate", fileId, file.status());
            return;
        }
        if (file.status() != SourceFileStatus.SEPARATING) {
            sourceFiles.updateStatus(fileId, SourceFileStatus.SEPARATING, null);
        }
        List<PageImageRef> pageRefs = pagesOf(file);
        List<PageRange> ranges = file.pageCount() == 1 ? List.of(new PageRange(1, 1)) : vote(file, pageRefs);
        finish(fileId, ranges, messageId);
    }

    /** The recorded pages, or every page converted, stored and recorded now, one page in memory at a time. */
    private List<PageImageRef> pagesOf(SourceFileRecord file) {
        List<PageRecord> recorded = pages.findBySourceFile(file.id());
        if (recorded.size() != file.pageCount()) {
            byte[] original = storage.get(file.s3Key());
            List<PageImage> converted = new ArrayList<>();
            conversion.forEachPngPage(original, detectedType(file), page -> {
                String key = keys.page(file.id(), page.pageNumber());
                storage.put(key, page.png(), "image/png");
                converted.add(new PageImage(page.pageNumber(), key, page.width(), page.height()));
            });
            if (converted.size() != file.pageCount()) {
                // The windows are cut from the counted pages, so a different number can't be separated
                throw new IllegalStateException("Source file " + file.id() + " converted into " + converted.size()
                        + " pages, but intake counted " + file.pageCount());
            }
            pages.saveAll(file.id(), converted);
            log.info("Converted source file {} into {} pages", file.id(), converted.size());
            recorded = pages.findBySourceFile(file.id());
        }
        return recorded.stream()
                .map(page -> new PageImageRef(page.pageNumber(), page.s3Key()))
                .toList();
    }

    private List<PageRange> vote(SourceFileRecord file, List<PageImageRef> pageRefs) {
        CallContext context = callContext(file);
        List<String> catalog =
                documentTypes.catalog().stream().map(CatalogEntry::name).toList();
        List<WindowRuns> windows = new ArrayList<>();
        for (PageRange window : votes.windows(file.pageCount(), settings.windowSize(), settings.windowOverlap())) {
            List<PageImageRef> windowPages = pageRefs.subList(window.start() - 1, window.end());
            windows.add(new WindowRuns(window, runs(file.id(), window, windowPages, context, catalog)));
        }
        return votes.voteWindows(windows, file.pageCount());
    }

    /**
     * The window's successful runs, as the file's page numbers, after making the calls still needed. Fewer than a
     * majority of {@code separation.runs} throws, so the inbox retries.
     */
    private List<SortedSet<Integer>> runs(
            UUID fileId, PageRange window, List<PageImageRef> windowPages, CallContext context, List<String> catalog) {
        List<SeparationRunRecord> saved = savedRuns(fileId, window);
        long successful = saved.stream().filter(SeparationRunRecord::succeeded).count();
        int needed = (int) Math.max(0, settings.runs() - successful);
        if (needed > 0) {
            int nextRun = saved.stream()
                            .mapToInt(SeparationRunRecord::runNumber)
                            .max()
                            .orElse(0)
                    + 1;
            call(fileId, window, windowPages, context, catalog, nextRun, needed);
            saved = savedRuns(fileId, window);
        }
        List<SortedSet<Integer>> sets = saved.stream()
                .filter(SeparationRunRecord::succeeded)
                .sorted(Comparator.comparingInt(SeparationRunRecord::runNumber))
                .limit(settings.runs())
                .map(run -> startPages(run, window))
                .toList();
        if (sets.size() < settings.runs() / 2 + 1) {
            throw new IllegalStateException("Too few successful separation runs for pages " + window.start() + "-"
                    + window.end() + " of source file " + fileId + ": " + sets.size() + " of " + settings.runs());
        }
        log.info(
                "Source file {} pages {}-{} have {} successful separation runs",
                fileId,
                window.start(),
                window.end(),
                sets.size());
        return sets;
    }

    /** The calls run at once on virtual threads; each run is saved as soon as it finishes, failed or not. */
    private void call(
            UUID fileId,
            PageRange window,
            List<PageImageRef> windowPages,
            CallContext context,
            List<String> catalog,
            int firstRun,
            int count) {
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> calls = new ArrayList<>();
            for (int run = firstRun; run < firstRun + count; run++) {
                int runNumber = run;
                calls.add(threads.submit(() -> {
                    NewSeparationRun result;
                    try {
                        SeparationAnswer answer = vision.separate(context, windowPages, catalog);
                        result = new NewSeparationRun(
                                runNumber, window.start(), window.end(), true, boundaries(answer, window));
                    } catch (VisionException e) {
                        log.info(
                                "Separation run {} for pages {}-{} of source file {} failed",
                                runNumber,
                                window.start(),
                                window.end(),
                                fileId);
                        result = new NewSeparationRun(runNumber, window.start(), window.end(), false, null);
                    }
                    separationRuns.save(fileId, result);
                }));
            }
            for (Future<?> call : calls) {
                waitFor(call);
            }
        }
    }

    private static void waitFor(Future<?> call) {
        try {
            call.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("A separation call failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while separating", e);
        }
    }

    /**
     * The model numbers the window's pages from 1; they are saved as the file's page numbers, uncleaned, with the
     * label at the same position (or none).
     */
    private static List<SeparationBoundary> boundaries(SeparationAnswer answer, PageRange window) {
        List<SeparationBoundary> boundaries = new ArrayList<>();
        List<Integer> starts = answer.startPages() == null ? List.of() : answer.startPages();
        List<String> labels = answer.labels() == null ? List.of() : answer.labels();
        for (int i = 0; i < starts.size(); i++) {
            Integer page = starts.get(i);
            if (page != null) {
                boundaries.add(
                        new SeparationBoundary(window.start() + page - 1, i < labels.size() ? labels.get(i) : null));
            }
        }
        return boundaries;
    }

    /** A saved run's start pages, cleaned in the window's own numbering and shifted back to the file's. */
    private SortedSet<Integer> startPages(SeparationRunRecord run, PageRange window) {
        int length = window.end() - window.start() + 1;
        List<Integer> withinWindow = run.boundaries().stream()
                .map(boundary -> boundary.startPage() - window.start() + 1)
                .toList();
        return votes.sanitize(withinWindow, length).stream()
                .map(page -> page + window.start() - 1)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private List<SeparationRunRecord> savedRuns(UUID fileId, PageRange window) {
        return separationRuns.findBySourceFile(fileId).stream()
                .filter(run -> run.windowStart() == window.start() && run.windowEnd() == window.end())
                .toList();
    }

    private CallContext callContext(SourceFileRecord file) {
        BatchRecord batch = batches.get(file.batchId());
        return CallContext.builder()
                .batchId(batch.id())
                .sourceFileId(file.id())
                .loanPurposeDescription(
                        loanPurposes.get(batch.loanPurposeCode()).description())
                .build();
    }

    /**
     * The documents, the file's new status, one classification message per document and the inbox message's done
     * mark, together. The file is locked and checked again first: another attempt may have separated it, or failed it
     * when the message was exhausted (after which its batch may be complete), while this one was calling the model.
     */
    private void finish(UUID fileId, List<PageRange> ranges, UUID messageId) {
        boolean separated = Boolean.TRUE.equals(transaction.execute(status -> {
            SourceFileStatus now = sourceFiles.lockForUpdate(fileId).status();
            if (now != SourceFileStatus.SEPARATING) {
                log.info("Source file {} became {} while it was being separated; keeping that", fileId, now);
                return false;
            }
            List<DocumentRecord> created = documents.createAll(fileId, ranges);
            sourceFiles.updateStatus(fileId, SourceFileStatus.SEPARATED, null);
            for (DocumentRecord document : created) {
                outbox.publish(
                        MessageType.CLASSIFY_DOCUMENT, document.id(), new ClassifyDocumentPayload(document.id()));
            }
            if (messageId != null) {
                inbox.markDone(messageId);
            }
            return true;
        }));
        if (separated) {
            log.info("Separated source file {} into {} documents", fileId, ranges.size());
        }
    }

    private static DetectedType detectedType(SourceFileRecord file) {
        return Arrays.stream(DetectedType.values())
                .filter(type -> type.mimeType().equals(file.contentType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Source file " + file.id() + " has an unsupported type " + file.contentType()));
    }
}
