package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.dto.CatalogEntry;
import com.bnaqica.documentreader.dto.ClassificationAnswer;
import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.NewClassificationRun;
import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.PageImage;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.dto.TypeMatchAnswer;
import com.bnaqica.documentreader.dto.response.ClassificationResultRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.ClassificationRound;
import com.bnaqica.documentreader.entity.DecidedBy;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.entity.DocumentTypeOrigin;
import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import com.bnaqica.documentreader.exception.VisionException;
import com.bnaqica.documentreader.processors.TypeVoteProcessor;
import com.bnaqica.documentreader.publisher.OutboxPublisher;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    ClockConfig.class,
    ClassificationServiceImpl.class,
    DocumentTypeCatalogServiceImpl.class,
    TypeVoteProcessor.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class,
    SourceFileServiceImpl.class,
    SourceFilePageServiceImpl.class,
    DocumentServiceImpl.class,
    DocumentTypeServiceImpl.class,
    DocumentTypeAliasServiceImpl.class,
    ClassificationRunServiceImpl.class,
    ClassificationResultServiceImpl.class,
    InboxMessageServiceImpl.class,
    OutboxPublisher.class,
    OutboxMessageServiceImpl.class,
    WorkerSignalServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@EnableConfigurationProperties(DocumentReaderProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ClassificationServiceTests {

    private static final String SUBJECT = "classification-tests";
    private static final String PAY_STUBS = "Pay stubs";
    private static final String W2 = "W-2 forms";
    private static final String BANK = "Bank statements";

    @Autowired
    private ClassificationServiceImpl classification;

    @Autowired
    private BatchService batches;

    @Autowired
    private SourceFileService sourceFiles;

    @Autowired
    private SourceFilePageService pages;

    @Autowired
    private DocumentService documents;

    @Autowired
    private DocumentTypeService documentTypes;

    @Autowired
    private ClassificationRunService runs;

    @Autowired
    private ClassificationResultService results;

    @Autowired
    private InboxMessageService inbox;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private VisionClient vision;

    @MockitoBean
    private BatchCompletionService completion;

    /** The pages each classify call was sent, and whether a transaction was open. */
    private final Queue<List<Integer>> classifyCalls = new ConcurrentLinkedQueue<>();

    private final Queue<List<String>> tiebreakCandidates = new ConcurrentLinkedQueue<>();
    private final Queue<Boolean> transactionActive = new ConcurrentLinkedQueue<>();

    @AfterEach
    void cleanUp() {
        ProcessingFixtures fixtures = new ProcessingFixtures(jdbc);
        List<UUID> batchIds = jdbc.sql("SELECT id FROM document_reader.batch WHERE requested_by = ?")
                .param(SUBJECT)
                .query(UUID.class)
                .list();
        for (UUID batch : batchIds) {
            jdbc.sql("""
                            DELETE FROM document_reader.outbox_message WHERE aggregate_id IN
                              (SELECT id FROM document_reader.document WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            jdbc.sql("""
                            DELETE FROM document_reader.inbox_message WHERE aggregate_id IN
                              (SELECT id FROM document_reader.document WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            jdbc.sql("""
                            DELETE FROM document_reader.classification_run WHERE document_id IN
                              (SELECT id FROM document_reader.document WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            jdbc.sql("""
                            DELETE FROM document_reader.classification_result WHERE document_id IN
                              (SELECT id FROM document_reader.document WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            jdbc.sql("""
                            UPDATE document_reader.document SET document_type_id = NULL WHERE batch_id = :batch
                            """).param("batch", batch).update();
        }
        CatalogCleanup.removeAllButSeeded(transactionManager, jdbc);
        for (UUID batch : batchIds) {
            jdbc.sql("""
                            DELETE FROM document_reader.source_file_page WHERE source_file_id IN
                              (SELECT id FROM document_reader.source_file WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            fixtures.deleteBatch(batch);
        }
    }

    // --- The vote

    @Test
    void savesResultWithAgreementAndPublishesExtract() {
        UUID document = document(2, 1, 2);
        classifyAnswers(PAY_STUBS, PAY_STUBS, W2, PAY_STUBS, PAY_STUBS);

        classification.classify(document);

        ClassificationResultRecord result = results.find(document).orElseThrow();
        assertThat(result.documentTypeId()).isEqualTo(type(PAY_STUBS));
        assertThat(result.agreement()).isEqualByComparingTo("0.8");
        assertThat(result.tieBroken()).isFalse();
        assertThat(result.decidedBy()).isEqualTo(DecidedBy.VOTE);
        assertThat(result.votes()).containsEntry(type(PAY_STUBS), 4).containsEntry(type(W2), 1);
        DocumentRecord classified = documents.get(document);
        assertThat(classified.status()).isEqualTo(DocumentStatus.CLASSIFIED);
        assertThat(classified.documentTypeId()).isEqualTo(type(PAY_STUBS));
        assertThat(extractMessages(document)).isEqualTo(1);
        assertThat(runs.findByDocument(document))
                .hasSize(5)
                .allSatisfy(run -> assertThat(run.round()).isEqualTo(ClassificationRound.INITIAL));
    }

    @Test
    void variantNamesCountAsOneType() {
        UUID document = document(1, 1, 1);
        classifyAnswers("Paystub", "Paystub", PAY_STUBS, PAY_STUBS, "W2 form");

        classification.classify(document);

        assertThat(results.find(document).orElseThrow().votes())
                .containsEntry(type(PAY_STUBS), 4)
                .containsEntry(type(W2), 1);
    }

    @Test
    void emptyKeyAnswerIsFailedRun() {
        UUID document = document(1, 1, 1);
        classifyAnswers(PAY_STUBS, PAY_STUBS, PAY_STUBS, W2, "N/A");

        classification.classify(document);

        assertThat(runs.findByDocument(document))
                .filteredOn(run -> "N/A".equals(run.rawAnswer()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.succeeded()).isFalse();
                    assertThat(run.documentTypeId()).isNull();
                });
        ClassificationResultRecord result = results.find(document).orElseThrow();
        assertThat(result.votes()).containsOnlyKeys(type(PAY_STUBS), type(W2));
        assertThat(result.agreement()).isEqualByComparingTo("0.75");
    }

    @Test
    void sendsFirstMaxPagesOfTheDocument() {
        UUID document = document(10, 4, 10);
        classifyAnswers(PAY_STUBS, PAY_STUBS, PAY_STUBS, PAY_STUBS, PAY_STUBS);

        classification.classify(document);

        assertThat(classifyCalls).hasSize(5).containsOnly(List.of(4, 5, 6));
    }

    @Test
    void callsRunOutsideTransactions() {
        UUID document = document(1, 1, 1);
        classifyAnswers(PAY_STUBS, PAY_STUBS, PAY_STUBS, PAY_STUBS, PAY_STUBS);

        classification.classify(document);

        assertThat(transactionActive).hasSize(5).containsOnly(false);
    }

    // --- Ties

    @Test
    void tiebreakRestrictedToTiedTypes() {
        UUID document = document(1, 1, 1);
        classifyAnswers(PAY_STUBS, W2, PAY_STUBS, W2, BANK);
        tiebreakAnswers(answer(W2, 0.9), answer(W2, 0.9), answer(PAY_STUBS, 0.9));

        classification.classify(document);

        assertThat(tiebreakCandidates)
                .hasSize(3)
                .allSatisfy(candidates -> assertThat(candidates).containsExactlyInAnyOrder(PAY_STUBS, W2));
        assertThat(runs.findByDocument(document))
                .filteredOn(run -> run.round() == ClassificationRound.TIEBREAK)
                .hasSize(3);
    }

    @Test
    void tiebreakWinnerWins() {
        UUID document = document(1, 1, 1);
        classifyAnswers(PAY_STUBS, W2, PAY_STUBS, W2, BANK);
        tiebreakAnswers(answer(W2, 0.6), answer(W2, 0.6), answer(PAY_STUBS, 0.99));

        classification.classify(document);

        ClassificationResultRecord result = results.find(document).orElseThrow();
        assertThat(result.documentTypeId()).isEqualTo(type(W2));
        assertThat(result.decidedBy()).isEqualTo(DecidedBy.TIEBREAK);
        assertThat(result.tieBroken()).isTrue();
        assertThat(result.votes()).containsEntry(type(PAY_STUBS), 2).containsEntry(type(W2), 2);
        assertThat(result.agreement()).isEqualByComparingTo("0.4");
    }

    @Test
    void stillTiedUsesHighestAverageConfidence() {
        UUID document = document(1, 1, 1);
        AtomicInteger call = new AtomicInteger();
        List<ClassificationAnswer> initial = List.of(answer(PAY_STUBS, 0.5), answer(W2, 0.5), answer(BANK, 0.5));
        given(vision.classify(any(), anyList(), anyList())).willAnswer(invocation -> {
            int n = call.getAndIncrement();
            if (n >= 3) {
                throw new VisionException(OpenAiCallPurpose.CLASSIFY, "timeout");
            }
            return initial.get(n);
        });
        tiebreakAnswers(answer(PAY_STUBS, 0.5), answer(W2, 0.95), answer(BANK, 0.6));

        classification.classify(document);

        ClassificationResultRecord result = results.find(document).orElseThrow();
        assertThat(result.documentTypeId()).isEqualTo(type(W2));
        assertThat(result.decidedBy()).isEqualTo(DecidedBy.CONFIDENCE);
        assertThat(result.tieBroken()).isTrue();
    }

    // --- Retries and new types

    @Test
    void belowMinSuccessfulRunsThrows() {
        UUID document = document(1, 1, 1);
        failAfter(2, PAY_STUBS);

        assertThatThrownBy(() -> classification.classify(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too few successful classification runs");
        assertThat(documents.get(document).status()).isEqualTo(DocumentStatus.CLASSIFYING);
        assertThat(results.find(document)).isEmpty();
        assertThat(extractMessages(document)).isZero();
    }

    @Test
    void retryReusesSuccessfulRuns() {
        UUID document = document(1, 1, 1);
        failAfter(2, PAY_STUBS);
        assertThatThrownBy(() -> classification.classify(document)).isInstanceOf(IllegalStateException.class);
        classifyCalls.clear();
        classifyAnswers(PAY_STUBS, PAY_STUBS, PAY_STUBS);

        classification.classify(document);

        assertThat(classifyCalls).hasSize(3);
        assertThat(results.find(document).orElseThrow().votes()).containsEntry(type(PAY_STUBS), 5);
    }

    @Test
    void newWinningNameIsCreatedAndAssigned() {
        UUID document = document(1, 1, 1);
        classifyAnswers("Moon rock appraisal", PAY_STUBS, "Moon rock appraisals", PAY_STUBS, "Moon-rock appraisal");
        given(vision.confirmNewType(any(), anyList(), any(), anyList())).willReturn(new TypeMatchAnswer(false, null));
        given(vision.draftSchema(any(), anyList(), any()))
                .willReturn(JsonMapper.builder()
                        .build()
                        .readTree(
                                "{\"type\":\"object\",\"properties\":{\"appraiser\":{\"type\":[\"string\",\"null\"]}},"
                                        + "\"required\":[\"appraiser\"],\"additionalProperties\":false}"));

        classification.classify(document);

        UUID created =
                documentTypes.resolve("Moon rock appraisal").orElseThrow().id();
        assertThat(documentTypes.get(created).origin()).isEqualTo(DocumentTypeOrigin.MODEL);
        assertThat(documents.get(document).documentTypeId()).isEqualTo(created);
        assertThat(results.find(document).orElseThrow().votes())
                .containsEntry(created, 3)
                .containsEntry(type(PAY_STUBS), 2);
    }

    @Test
    void winningNameConfirmedAsExistingCountsAllItsVotes() {
        UUID document = document(1, 1, 1);
        classifyAnswers("Earnings statement", PAY_STUBS, "Earnings statements", PAY_STUBS, "Earnings statement");
        given(vision.confirmNewType(any(), anyList(), any(), anyList()))
                .willReturn(new TypeMatchAnswer(true, PAY_STUBS));

        classification.classify(document);

        ClassificationResultRecord result = results.find(document).orElseThrow();
        assertThat(result.documentTypeId()).isEqualTo(type(PAY_STUBS));
        assertThat(result.votes()).containsOnly(Map.entry(type(PAY_STUBS), 5));
        assertThat(result.agreement()).isEqualByComparingTo("1.0");
    }

    @Test
    void overlongNameIsFailedRun() {
        UUID document = document(1, 1, 1);
        String sentence = "A ".repeat(150) + "statement";
        classifyAnswers(sentence, PAY_STUBS, sentence, PAY_STUBS, PAY_STUBS);

        classification.classify(document);

        assertThat(runs.findByDocument(document))
                .filteredOn(run -> sentence.equals(run.rawAnswer()))
                .hasSize(2)
                .allSatisfy(run -> assertThat(run.succeeded()).isFalse());
        assertThat(results.find(document).orElseThrow().documentTypeId()).isEqualTo(type(PAY_STUBS));
        then(vision).should(never()).confirmNewType(any(), anyList(), any(), anyList());
    }

    @Test
    void savedRunKeepsItsTypeEvenIfItsAnswerNoLongerResolves() {
        UUID document = document(1, 1, 1);
        // Runs saved as Pay stubs under a name the catalog has since lost (renamed without an alias)
        for (int run = 1; run <= 5; run++) {
            runs.save(
                    document,
                    new NewClassificationRun(
                            ClassificationRound.INITIAL,
                            run,
                            "Old pay slip name",
                            type(PAY_STUBS),
                            BigDecimal.ONE,
                            "why",
                            true));
        }

        classification.classify(document);

        assertThat(results.find(document).orElseThrow().documentTypeId()).isEqualTo(type(PAY_STUBS));
        then(vision).shouldHaveNoInteractions();
    }

    @Test
    void tiebreakAnswerOutsideTheTiedCandidatesIsFailedRun() {
        UUID document = document(1, 1, 1);
        classifyAnswers(PAY_STUBS, W2, PAY_STUBS, W2, BANK);
        tiebreakAnswers(answer(BANK, 0.9), answer(W2, 0.9), answer(W2, 0.9));

        classification.classify(document);

        assertThat(runs.findByDocument(document))
                .filteredOn(run -> run.round() == ClassificationRound.TIEBREAK && BANK.equals(run.rawAnswer()))
                .singleElement()
                .satisfies(run -> assertThat(run.succeeded()).isFalse());
        assertThat(results.find(document).orElseThrow().documentTypeId()).isEqualTo(type(W2));
    }

    @Test
    void idempotentWhenResultExists() {
        UUID document = document(1, 1, 1);
        classifyAnswers(PAY_STUBS, PAY_STUBS, PAY_STUBS, PAY_STUBS, PAY_STUBS);
        classification.classify(document);
        classifyCalls.clear();

        classification.classify(document);

        assertThat(classifyCalls).isEmpty();
        assertThat(extractMessages(document)).isEqualTo(1);
    }

    @Test
    void documentFailedWhileClassifyingGetsNoResult() {
        UUID document = document(1, 1, 1);
        AtomicInteger call = new AtomicInteger();
        given(vision.classify(any(), anyList(), anyList())).willAnswer(invocation -> {
            if (call.getAndIncrement() == 0) {
                // another attempt exhausts the message meanwhile
                inTransaction(() -> documents.updateStatus(document, DocumentStatus.FAILED, "Classification failed"));
            }
            return answer(PAY_STUBS, 0.9);
        });

        classification.classify(document);

        assertThat(results.find(document)).isEmpty();
        assertThat(extractMessages(document)).isZero();
        assertThat(documents.get(document).status()).isEqualTo(DocumentStatus.FAILED);
    }

    // --- Inbox handler

    @Test
    void handleClassifiesAndMarksMessageDone() {
        UUID document = document(1, 1, 1);
        classifyAnswers(PAY_STUBS, PAY_STUBS, PAY_STUBS, PAY_STUBS, PAY_STUBS);
        InboxMessageRecord message = inboxMessage(document);

        assertThat(classification.type()).isEqualTo(MessageType.CLASSIFY_DOCUMENT);
        classification.handle(message);

        assertThat(documents.get(document).status()).isEqualTo(DocumentStatus.CLASSIFIED);
        assertThat(inbox.get(message.id()).status()).isEqualTo(InboxStatus.DONE);
    }

    @Test
    void exhaustedFailsDocumentAndRunsCompletionCheck() {
        UUID document = document(1, 1, 1);
        inTransaction(() -> documents.updateStatus(document, DocumentStatus.CLASSIFYING, null));

        inTransaction(() -> classification.onExhausted(inboxMessage(document), "VisionException: timeout"));

        DocumentRecord failed = documents.get(document);
        assertThat(failed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("Classification failed");
        then(completion).should().onDocumentFinished(document);
    }

    @Test
    void exhaustedLeavesClassifiedDocumentAlone() {
        UUID document = document(1, 1, 1);
        classifyAnswers(PAY_STUBS, PAY_STUBS, PAY_STUBS, PAY_STUBS, PAY_STUBS);
        classification.classify(document);

        inTransaction(() -> classification.onExhausted(inboxMessage(document), "late"));

        assertThat(documents.get(document).status()).isEqualTo(DocumentStatus.CLASSIFIED);
        then(completion).should(never()).onDocumentFinished(any());
    }

    // --- Helpers

    private void classifyAnswers(String... names) {
        AtomicInteger call = new AtomicInteger();
        willAnswer(invocation -> {
                    record(invocation.getArgument(1));
                    return answer(names[call.getAndIncrement()], 0.9);
                })
                .given(vision)
                .classify(any(), anyList(), anyList());
    }

    /** The first {@code succeeding} calls answer {@code name}; every later one fails. */
    private void failAfter(int succeeding, String name) {
        AtomicInteger call = new AtomicInteger();
        willAnswer(invocation -> {
                    record(invocation.getArgument(1));
                    if (call.getAndIncrement() < succeeding) {
                        return answer(name, 0.9);
                    }
                    throw new VisionException(OpenAiCallPurpose.CLASSIFY, "timeout");
                })
                .given(vision)
                .classify(any(), anyList(), anyList());
    }

    private void tiebreakAnswers(ClassificationAnswer... answers) {
        AtomicInteger call = new AtomicInteger();
        given(vision.tiebreak(any(), anyList(), anyList())).willAnswer(invocation -> {
            List<CatalogEntry> candidates = invocation.getArgument(2);
            tiebreakCandidates.add(candidates.stream().map(CatalogEntry::name).toList());
            return answers[call.getAndIncrement()];
        });
    }

    private void record(List<PageImageRef> sent) {
        transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
        classifyCalls.add(sent.stream().map(PageImageRef::pageNumber).toList());
    }

    private static ClassificationAnswer answer(String name, double confidence) {
        return new ClassificationAnswer(name, confidence, "Because");
    }

    private UUID type(String name) {
        return documentTypes.resolve(name).orElseThrow().id();
    }

    /** A pending document covering {@code start}–{@code end} of a file of {@code pageCount} recorded pages. */
    private UUID document(int pageCount, int start, int end) {
        return committed(() -> {
            UUID batch = batches.create(new NewBatch("CAR_REFINANCE", false, null, SUBJECT, 1))
                    .id();
            batches.updateStatus(batch, BatchStatus.PROCESSING, null);
            UUID file = sourceFiles
                    .createAll(
                            batch,
                            List.of(new NewSourceFile("file.pdf", "application/pdf", 10, "a".repeat(64), pageCount)))
                    .getFirst()
                    .id();
            List<PageImage> recorded = new ArrayList<>();
            for (int page = 1; page <= pageCount; page++) {
                recorded.add(new PageImage(page, "files/" + file + "/pages/" + page + ".png", 100, 100));
            }
            pages.saveAll(file, recorded);
            return documents
                    .createAll(file, List.of(new PageRange(start, end)))
                    .getFirst()
                    .id();
        });
    }

    private int extractMessages(UUID document) {
        return jdbc.sql("""
                        SELECT count(*) FROM document_reader.outbox_message
                        WHERE message_type = 'EXTRACT_DOCUMENT' AND aggregate_id = ?
                        """).param(document).query(Integer.class).single();
    }

    private InboxMessageRecord inboxMessage(UUID document) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.inbox_message
                            (id, message_type, aggregate_id, payload, status, attempts, next_attempt_at, created_at)
                        VALUES (?, 'CLASSIFY_DOCUMENT', ?, CAST(? AS jsonb), 'PROCESSING', 1, now(), now())
                        """)
                .params(id, document, "{\"documentId\":\"" + document + "\"}")
                .update();
        return inbox.get(id);
    }

    private <T> T committed(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }
}
