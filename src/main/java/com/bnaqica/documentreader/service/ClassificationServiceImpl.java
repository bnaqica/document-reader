package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.dto.CallContext;
import com.bnaqica.documentreader.dto.CatalogEntry;
import com.bnaqica.documentreader.dto.ClassificationAnswer;
import com.bnaqica.documentreader.dto.NewClassificationResult;
import com.bnaqica.documentreader.dto.NewClassificationRun;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.event.ExtractDocumentPayload;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.ClassificationRunRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.ClassificationRound;
import com.bnaqica.documentreader.entity.DecidedBy;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.exception.VisionException;
import com.bnaqica.documentreader.processors.TypeNameNormalizer;
import com.bnaqica.documentreader.processors.TypeVoteProcessor;
import com.bnaqica.documentreader.processors.TypeVoteProcessor.ResolvedVote;
import com.bnaqica.documentreader.processors.TypeVoteProcessor.VoteOutcome;
import com.bnaqica.documentreader.publisher.OutboxPublisher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Process 2 (§11), and the {@code CLASSIFY_DOCUMENT} inbox handler. Every answer is resolved to a candidate before
 * it is counted: a catalog type, or a name the catalog doesn't have yet. The most votes wins; a tie gets a round
 * among the tied candidates, and a tie that remains goes to the highest average confidence. A winning name that isn't
 * a type goes through the catalog service, which adds it once. Every model call runs outside a transaction, and every
 * run is saved as it finishes, so a retry repeats only what is missing.
 */
@Slf4j
@Service
public class ClassificationServiceImpl implements ClassificationService, InboxMessageHandler {

    static final String CLASSIFICATION_FAILED = "Classification failed";
    static final String NEW_CANDIDATE_DESCRIPTION = "A kind of document not in the catalog yet";

    /** {@code document_type.name} and {@code document_type_alias.alias} hold at most this many characters. */
    static final int MAX_NAME_LENGTH = 200;

    /** Past these, a document has been classified (or failed); classification never touches it again. */
    private static final Set<DocumentStatus> DONE = Set.of(
            DocumentStatus.CLASSIFIED, DocumentStatus.EXTRACTING, DocumentStatus.COMPLETED, DocumentStatus.FAILED);

    private final DocumentService documents;
    private final SourceFilePageService pages;
    private final BatchService batches;
    private final LoanPurposeService loanPurposes;
    private final DocumentTypeService documentTypes;
    private final DocumentTypeCatalogService catalogService;
    private final ClassificationRunService runs;
    private final ClassificationResultService results;
    private final InboxMessageService inbox;
    private final BatchCompletionService completion;
    private final OutboxPublisher outbox;
    private final VisionClient vision;
    private final TypeVoteProcessor votes;
    private final DocumentReaderProperties.Classification settings;
    private final TransactionTemplate transaction;

    public ClassificationServiceImpl(
            DocumentService documents,
            SourceFilePageService pages,
            BatchService batches,
            LoanPurposeService loanPurposes,
            DocumentTypeService documentTypes,
            DocumentTypeCatalogService catalogService,
            ClassificationRunService runs,
            ClassificationResultService results,
            InboxMessageService inbox,
            BatchCompletionService completion,
            OutboxPublisher outbox,
            VisionClient vision,
            TypeVoteProcessor votes,
            DocumentReaderProperties properties,
            PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.pages = pages;
        this.batches = batches;
        this.loanPurposes = loanPurposes;
        this.documentTypes = documentTypes;
        this.catalogService = catalogService;
        this.runs = runs;
        this.results = results;
        this.inbox = inbox;
        this.completion = completion;
        this.outbox = outbox;
        this.vision = vision;
        this.votes = votes;
        this.settings = properties.classification();
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** One thing a run can vote for: a catalog type, or (with no id) a name the catalog doesn't have yet. */
    private record Candidate(String key, UUID typeId, String name, String description) {}

    /** A counted run: what it voted for and how confident it was. */
    private record Vote(Candidate candidate, double confidence) {}

    @Override
    public MessageType type() {
        return MessageType.CLASSIFY_DOCUMENT;
    }

    @Override
    public void handle(InboxMessageRecord message) {
        classify(message.aggregateId(), message.id());
    }

    @Override
    public void classify(UUID documentId) {
        classify(documentId, null);
    }

    /**
     * Runs in the inbox's {@code FAILED} transaction. The document is locked first, as the final transaction locks
     * it, so of a failure and a late attempt's result only the first to commit counts.
     */
    @Override
    public void onExhausted(InboxMessageRecord message, String lastError) {
        UUID documentId = message.aggregateId();
        DocumentStatus now = documents.lockForUpdate(documentId).status();
        if (DONE.contains(now)) {
            log.debug("Document {} is already {}; not failing it", documentId, now);
            return;
        }
        // The inbox's last error isn't copied: callers see this reason in the batch result
        documents.updateStatus(documentId, DocumentStatus.FAILED, CLASSIFICATION_FAILED);
        completion.onDocumentFinished(documentId);
    }

    private void classify(UUID documentId, UUID messageId) {
        DocumentRecord document = documents.get(documentId);
        if (DONE.contains(document.status()) || results.find(documentId).isPresent()) {
            log.debug("Document {} is already {}; nothing to classify", documentId, document.status());
            return;
        }
        if (document.status() != DocumentStatus.CLASSIFYING && !startClassifying(documentId)) {
            return;
        }
        List<PageImageRef> firstPages = firstPages(document);
        CallContext context = callContext(document);
        List<CatalogEntry> catalog = documentTypes.catalog();

        List<Vote> initial = round(
                documentId,
                ClassificationRound.INITIAL,
                settings.runs(),
                () -> vision.classify(context, firstPages, catalog),
                null);
        if (initial.size() < settings.minSuccessfulRuns()) {
            throw new IllegalStateException("Too few successful classification runs for document " + documentId + ": "
                    + initial.size() + " of " + settings.runs() + ", at least " + settings.minSuccessfulRuns()
                    + " needed");
        }
        VoteOutcome first = votes.tally(resolved(initial));
        Candidate winner;
        DecidedBy decidedBy;
        if (first.winner().isPresent()) {
            winner = candidate(initial, first.winner().get());
            decidedBy = DecidedBy.VOTE;
        } else {
            List<Candidate> tied =
                    first.tied().stream().map(key -> candidate(initial, key)).toList();
            List<CatalogEntry> offered = tied.stream()
                    .map(candidate -> new CatalogEntry(candidate.name(), candidate.description()))
                    .toList();
            List<Vote> tiebreak = round(
                    documentId,
                    ClassificationRound.TIEBREAK,
                    settings.tiebreakRuns(),
                    () -> vision.tiebreak(context, firstPages, offered),
                    first.tied());
            VoteOutcome second = votes.tally(resolved(tiebreak));
            if (second.winner().isPresent()) {
                winner = candidate(tiebreak, second.winner().get());
                decidedBy = DecidedBy.TIEBREAK;
            } else {
                List<Vote> both = new ArrayList<>(initial);
                both.addAll(tiebreak);
                winner = candidate(initial, votes.byConfidence(resolved(both), first.tied()));
                decidedBy = DecidedBy.CONFIDENCE;
            }
        }
        UUID typeId = winner.typeId() != null
                ? winner.typeId()
                : catalogService
                        .createOrMatch(context, firstPages, winner.name())
                        .id();
        finish(documentId, messageId, initial, first, winner, typeId, decidedBy);
    }

    /**
     * Makes the round's calls still needed and returns its counted votes. Each answer is resolved as soon as it comes
     * back and saved with its type; an answer with no usable name key (or, in a tie-break, outside the tied
     * candidates) is saved as a failed run.
     */
    private List<Vote> round(
            UUID documentId,
            ClassificationRound round,
            int wanted,
            Supplier<ClassificationAnswer> call,
            List<String> allowed) {
        List<ClassificationRunRecord> saved = savedRuns(documentId, round);
        long successful =
                saved.stream().filter(ClassificationRunRecord::succeeded).count();
        int needed = (int) Math.max(0, wanted - successful);
        if (needed > 0) {
            int nextRun = saved.stream()
                            .mapToInt(ClassificationRunRecord::runNumber)
                            .max()
                            .orElse(0)
                    + 1;
            makeCalls(documentId, round, call, allowed, nextRun, needed);
            saved = savedRuns(documentId, round);
        }
        return saved.stream()
                .filter(ClassificationRunRecord::succeeded)
                .sorted(Comparator.comparingInt(ClassificationRunRecord::runNumber))
                .limit(wanted)
                .map(run -> new Vote(candidateOf(run), confidenceOf(run)))
                .filter(vote ->
                        allowed == null || allowed.contains(vote.candidate().key()))
                .toList();
    }

    private void makeCalls(
            UUID documentId,
            ClassificationRound round,
            Supplier<ClassificationAnswer> call,
            List<String> allowed,
            int firstRun,
            int count) {
        Semaphore permits = new Semaphore(settings.parallelism());
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> calls = new ArrayList<>();
            for (int run = firstRun; run < firstRun + count; run++) {
                int runNumber = run;
                calls.add(threads.submit(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        runs.save(documentId, runOf(documentId, round, runNumber, call, allowed));
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<?> future : calls) {
                waitFor(future);
            }
        }
    }

    private NewClassificationRun runOf(
            UUID documentId,
            ClassificationRound round,
            int runNumber,
            Supplier<ClassificationAnswer> call,
            List<String> allowed) {
        ClassificationAnswer answer;
        try {
            answer = call.get();
        } catch (VisionException e) {
            log.warn("Classification run {} ({}) of document {} failed", runNumber, round, documentId);
            return new NewClassificationRun(round, runNumber, null, null, null, null, false);
        }
        String name = answer.documentType();
        BigDecimal confidence =
                BigDecimal.valueOf(Math.clamp(answer.confidence(), 0.0, 1.0)).setScale(3, RoundingMode.HALF_UP);
        Optional<Candidate> candidate = resolve(name);
        boolean counts = candidate.isPresent()
                && (allowed == null || allowed.contains(candidate.get().key()));
        UUID typeId = candidate.map(Candidate::typeId).orElse(null);
        // Review Focus #3: a name with no key (or outside the tie) is a failed run, never a new type
        return new NewClassificationRun(round, runNumber, name, typeId, confidence, answer.reasoning(), counts);
    }

    /**
     * A name's candidate: the type it resolves to, or itself as a new name; empty when it has no usable key, or is too
     * long to ever become a type's name or alias.
     */
    private Optional<Candidate> resolve(String name) {
        if (name == null || name.strip().length() > MAX_NAME_LENGTH) {
            return Optional.empty();
        }
        Optional<DocumentTypeResponse> type = documentTypes.resolve(name);
        if (type.isPresent()) {
            DocumentTypeResponse found = type.get();
            return Optional.of(new Candidate(found.normalizedKey(), found.id(), found.name(), found.description()));
        }
        return TypeNameNormalizer.key(name)
                .map(key -> new Candidate(key, null, name.strip(), NEW_CANDIDATE_DESCRIPTION));
    }

    /**
     * A saved run's candidate: the type it was saved with, or, for a name the catalog didn't have then, whatever that
     * name resolves to now (another document may have added it meanwhile).
     */
    private Candidate candidateOf(ClassificationRunRecord run) {
        if (run.documentTypeId() != null) {
            DocumentTypeResponse type = documentTypes.get(run.documentTypeId());
            return new Candidate(type.normalizedKey(), type.id(), type.name(), type.description());
        }
        return resolve(run.rawAnswer()).orElseThrow();
    }

    private static List<ResolvedVote> resolved(List<Vote> cast) {
        return cast.stream()
                .map(vote -> new ResolvedVote(vote.candidate().key(), vote.confidence()))
                .toList();
    }

    /** The candidate behind a key, as the first vote for it named it. */
    private static Candidate candidate(List<Vote> cast, String key) {
        return cast.stream()
                .map(Vote::candidate)
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    /**
     * The result, the type, the status, the extraction message and the inbox message's done mark, together. The
     * document is locked and checked again first: another attempt may have classified or failed it meanwhile.
     */
    private void finish(
            UUID documentId,
            UUID messageId,
            List<Vote> initial,
            VoteOutcome first,
            Candidate winner,
            UUID typeId,
            DecidedBy decidedBy) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        first.counts().forEach((key, count) -> {
            UUID id =
                    key.equals(winner.key()) ? typeId : candidate(initial, key).typeId();
            if (id != null) { // a losing name the catalog doesn't have has no id to count under
                counts.merge(id, count.intValue(), Integer::sum);
            }
        });
        // From the merged count: a winning name that turned out to be another candidate's alias carries its votes
        BigDecimal agreement = BigDecimal.valueOf(counts.getOrDefault(typeId, 0))
                .divide(BigDecimal.valueOf(initial.size()), 3, RoundingMode.HALF_UP);
        boolean classified = Boolean.TRUE.equals(transaction.execute(status -> {
            DocumentStatus now = documents.lockForUpdate(documentId).status();
            if (now != DocumentStatus.CLASSIFYING) {
                log.info("Document {} became {} while it was being classified; keeping that", documentId, now);
                return false;
            }
            results.save(
                    documentId,
                    new NewClassificationResult(typeId, counts, agreement, decidedBy != DecidedBy.VOTE, decidedBy));
            documents.assignType(documentId, typeId);
            documents.updateStatus(documentId, DocumentStatus.CLASSIFIED, null);
            outbox.publish(MessageType.EXTRACT_DOCUMENT, documentId, new ExtractDocumentPayload(documentId));
            if (messageId != null) {
                inbox.markDone(messageId);
            }
            return true;
        }));
        if (classified) {
            log.info("Classified document {} as type {} by {}", documentId, typeId, decidedBy);
        }
    }

    /**
     * {@code PENDING} to {@code CLASSIFYING} under the document's lock, so an attempt that read the document before its
     * message was exhausted can't overwrite the failure. False when the document has moved on meanwhile.
     */
    private boolean startClassifying(UUID documentId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            DocumentStatus now = documents.lockForUpdate(documentId).status();
            if (now == DocumentStatus.PENDING) {
                documents.updateStatus(documentId, DocumentStatus.CLASSIFYING, null);
                return true;
            }
            log.debug("Document {} is already {}; not classifying it", documentId, now);
            return now == DocumentStatus.CLASSIFYING;
        }));
    }

    /** The document's first {@code classification.max-pages} pages, from its file's recorded pages. */
    private List<PageImageRef> firstPages(DocumentRecord document) {
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

    private List<ClassificationRunRecord> savedRuns(UUID documentId, ClassificationRound round) {
        return runs.findByDocument(documentId).stream()
                .filter(run -> run.round() == round)
                .toList();
    }

    private static double confidenceOf(ClassificationRunRecord run) {
        return run.confidence() == null ? 0 : run.confidence().doubleValue();
    }

    private static void waitFor(Future<?> call) {
        try {
            call.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("A classification call failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while classifying", e);
        }
    }
}
