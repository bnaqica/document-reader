package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.dto.ConsolidatedAnswer;
import com.bnaqica.documentreader.dto.ExtractionAnswer;
import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.PageImage;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.ExtractionResultRecord;
import com.bnaqica.documentreader.dto.response.ExtractionRunRecord;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.entity.ExtractionStage;
import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import com.bnaqica.documentreader.exception.VisionException;
import com.bnaqica.documentreader.processors.DataSchemaValidator;
import com.bnaqica.documentreader.publisher.OutboxPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    ClockConfig.class,
    ExtractionServiceImpl.class,
    DataSchemaValidator.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class,
    SourceFileServiceImpl.class,
    SourceFilePageServiceImpl.class,
    DocumentServiceImpl.class,
    DocumentTypeServiceImpl.class,
    ExtractionRunServiceImpl.class,
    ExtractionResultServiceImpl.class,
    InboxMessageServiceImpl.class,
    OutboxPublisher.class,
    OutboxMessageServiceImpl.class,
    WorkerSignalServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@EnableConfigurationProperties(DocumentReaderProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExtractionServiceTests {

    private static final String SUBJECT = "extraction-tests";

    @Autowired
    private ExtractionServiceImpl extraction;

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
    private ExtractionRunService runs;

    @Autowired
    private ExtractionResultService results;

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

    /** The pages each call that sends pages got, and whether a transaction was open, for every model call. */
    private final Queue<List<Integer>> pagesSent = new ConcurrentLinkedQueue<>();

    private final Queue<Boolean> transactionActive = new ConcurrentLinkedQueue<>();
    private final Queue<List<String>> validationErrorsSent = new ConcurrentLinkedQueue<>();
    private final Queue<ConsolidatedAnswer> draftsSent = new ConcurrentLinkedQueue<>();

    private JsonNode schema;
    private UUID payStubs;

    @BeforeEach
    void payStubsSchema() {
        payStubs = documentTypes.resolve("Pay stubs").orElseThrow().id();
        schema = documentTypes.get(payStubs).dataSchema();
        answerPasses(() -> new ExtractionAnswer(validData("Acme"), "A pay stub"));
        answerConsolidation(new ConsolidatedAnswer(validData("Acme"), "Draft", List.of("grossPay")));
        answerFinals(() -> new ExtractionAnswer(validData("Acme Corp"), "Bi-weekly pay stub"));
    }

    @AfterEach
    void cleanUp() {
        ProcessingFixtures fixtures = new ProcessingFixtures(jdbc);
        for (UUID batch : jdbc.sql("SELECT id FROM document_reader.batch WHERE requested_by = ?")
                .param(SUBJECT)
                .query(UUID.class)
                .list()) {
            jdbc.sql("""
                            DELETE FROM document_reader.inbox_message WHERE aggregate_id IN
                              (SELECT id FROM document_reader.document WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            jdbc.sql("""
                            DELETE FROM document_reader.source_file_page WHERE source_file_id IN
                              (SELECT id FROM document_reader.source_file WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            fixtures.deleteBatch(batch);
        }
    }

    // --- The stages

    @Test
    void threeInitialPassesThenConsolidateThenFinal() {
        UUID document = classifiedDocument(3, 1, 3);

        extraction.extract(document);

        List<ExtractionRunRecord> saved = runs.findByDocument(document);
        assertThat(saved)
                .extracting(ExtractionRunRecord::stage)
                .containsExactly(
                        ExtractionStage.INITIAL,
                        ExtractionStage.INITIAL,
                        ExtractionStage.INITIAL,
                        ExtractionStage.CONSOLIDATED,
                        ExtractionStage.FINAL);
        assertThat(saved.getLast().summary()).isEqualTo("Bi-weekly pay stub");
        ExtractionResultRecord result = results.find(document).orElseThrow();
        assertThat(result.documentTypeId()).isEqualTo(payStubs);
        assertThat(result.schemaVersion()).isEqualTo(documentTypes.get(payStubs).schemaVersion());
        assertThat(result.data().get("employerName").asString()).isEqualTo("Acme Corp");
        assertThat(result.summary()).isEqualTo("Bi-weekly pay stub");
        DocumentRecord completed = documents.get(document);
        assertThat(completed.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(completed.truncated()).isFalse();
    }

    @Test
    void finalReceivesDraftAndImages() {
        UUID document = classifiedDocument(3, 1, 3);

        extraction.extract(document);

        assertThat(draftsSent).singleElement().satisfies(draft -> {
            assertThat(draft.summary()).isEqualTo("Draft");
            assertThat(draft.disagreements()).containsExactly("grossPay");
        });
        // 3 passes and 1 final send pages; consolidation is text only
        assertThat(pagesSent).hasSize(4).containsOnly(List.of(1, 2, 3));
        ArgumentCaptor<List<ExtractionAnswer>> consolidated = ArgumentCaptor.captor();
        then(vision).should().consolidate(any(), eq(schema), consolidated.capture());
        assertThat(consolidated.getValue())
                .hasSize(3)
                .extracting(ExtractionAnswer::summary)
                .containsOnly("A pay stub");
    }

    @Test
    void callsRunOutsideTransactions() {
        extraction.extract(classifiedDocument(1, 1, 1));

        assertThat(transactionActive).hasSize(5).containsOnly(false);
    }

    @Test
    void completedDocumentRunsCompletionCheck() {
        UUID document = classifiedDocument(1, 1, 1);
        List<Boolean> seenInside = new ArrayList<>();
        willAnswer(call -> {
                    seenInside.add(TransactionSynchronizationManager.isActualTransactionActive());
                    seenInside.add(results.find(document).isPresent());
                    seenInside.add(documents.get(document).status() == DocumentStatus.COMPLETED);
                    return null;
                })
                .given(completion)
                .onDocumentFinished(document);

        extraction.extract(document);

        then(completion).should(times(1)).onDocumentFinished(document);
        assertThat(seenInside).containsExactly(true, true, true);
    }

    // --- The corrective retry

    @Test
    void invalidFinalRetriedOnceWithErrors() {
        UUID document = classifiedDocument(1, 1, 1);
        AtomicInteger call = new AtomicInteger();
        answerFinals(() -> call.getAndIncrement() == 0
                ? new ExtractionAnswer(invalidData(), "Missing a field")
                : new ExtractionAnswer(validData("Fixed"), "Corrected"));

        extraction.extract(document);

        assertThat(validationErrorsSent).hasSize(2);
        assertThat(validationErrorsSent.peek()).isEmpty();
        assertThat(List.copyOf(validationErrorsSent).get(1))
                .isNotEmpty()
                .anySatisfy(error -> assertThat(error).contains("netPay"));
        assertThat(results.find(document).orElseThrow().summary()).isEqualTo("Corrected");
        assertThat(runs.findByDocumentAndStage(document, ExtractionStage.FINAL)).hasSize(1);
    }

    @Test
    void invalidTwiceThrows() {
        UUID document = classifiedDocument(1, 1, 1);
        answerFinals(() -> new ExtractionAnswer(invalidData(), "Missing a field"));

        assertThatThrownBy(() -> extraction.extract(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed validation twice")
                .hasMessageNotContaining("netPay");
        assertThat(results.find(document)).isEmpty();
        assertThat(documents.get(document).status()).isEqualTo(DocumentStatus.EXTRACTING);
        then(completion).shouldHaveNoInteractions();
    }

    // --- Truncation and retries

    @Test
    void longDocumentTruncatedAt20Pages() {
        UUID document = classifiedDocument(30, 3, 27);

        extraction.extract(document);

        List<Integer> first20 = new ArrayList<>();
        for (int page = 3; page <= 22; page++) {
            first20.add(page);
        }
        assertThat(pagesSent).hasSize(4).containsOnly(first20);
        assertThat(documents.get(document).truncated()).isTrue();
    }

    @Test
    void retryReusesSavedPasses() {
        UUID document = classifiedDocument(1, 1, 1);
        AtomicInteger pass = new AtomicInteger();
        answerPasses(() -> {
            if (pass.getAndIncrement() == 0) {
                throw new VisionException(OpenAiCallPurpose.EXTRACT, "timeout");
            }
            return new ExtractionAnswer(validData("Acme"), "A pay stub");
        });
        assertThatThrownBy(() -> extraction.extract(document)).isInstanceOf(VisionException.class);
        assertThat(runs.findByDocumentAndStage(document, ExtractionStage.INITIAL))
                .hasSize(2);
        answerPasses(() -> new ExtractionAnswer(validData("Acme"), "A pay stub"));
        pagesSent.clear();

        extraction.extract(document);

        assertThat(pagesSent).hasSize(2); // one more pass, then the final
        assertThat(runs.findByDocumentAndStage(document, ExtractionStage.INITIAL))
                .hasSize(3);
        assertThat(documents.get(document).status()).isEqualTo(DocumentStatus.COMPLETED);
    }

    @Test
    void everyFailedPassIsReported() {
        UUID document = classifiedDocument(1, 1, 1);
        answerPasses(() -> {
            throw new VisionException(OpenAiCallPurpose.EXTRACT, "timeout");
        });

        assertThatThrownBy(() -> extraction.extract(document))
                .isInstanceOf(VisionException.class)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).hasSize(2));
    }

    @Test
    void idempotentWhenResultExists() {
        UUID document = classifiedDocument(1, 1, 1);
        extraction.extract(document);
        pagesSent.clear();

        extraction.extract(document);

        assertThat(pagesSent).isEmpty();
        assertThat(runs.findByDocument(document)).hasSize(5);
        then(completion).should(times(1)).onDocumentFinished(document);
    }

    @Test
    void documentFailedWhileExtractingGetsNoResult() {
        UUID document = classifiedDocument(1, 1, 1);
        AtomicInteger call = new AtomicInteger();
        answerFinals(() -> {
            if (call.getAndIncrement() == 0) {
                inTransaction(() -> documents.updateStatus(document, DocumentStatus.FAILED, "Extraction failed"));
            }
            return new ExtractionAnswer(validData("Acme"), "Late");
        });

        extraction.extract(document);

        assertThat(results.find(document)).isEmpty();
        assertThat(documents.get(document).status()).isEqualTo(DocumentStatus.FAILED);
        then(completion).should(never()).onDocumentFinished(any());
    }

    // --- Inbox handler

    @Test
    void handleExtractsAndMarksMessageDone() {
        UUID document = classifiedDocument(1, 1, 1);
        InboxMessageRecord message = inboxMessage(document);

        assertThat(extraction.type()).isEqualTo(MessageType.EXTRACT_DOCUMENT);
        extraction.handle(message);

        assertThat(documents.get(document).status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(inbox.get(message.id()).status()).isEqualTo(InboxStatus.DONE);
    }

    @Test
    void exhaustedFailsDocumentAndRunsCompletionCheck() {
        UUID document = classifiedDocument(1, 1, 1);
        inTransaction(() -> documents.updateStatus(document, DocumentStatus.EXTRACTING, null));

        inTransaction(() -> extraction.onExhausted(inboxMessage(document), "VisionException: timeout"));

        DocumentRecord failed = documents.get(document);
        assertThat(failed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("Extraction failed");
        then(completion).should().onDocumentFinished(document);
    }

    @Test
    void exhaustedLeavesFailedDocumentAlone() {
        UUID document = classifiedDocument(1, 1, 1);
        inTransaction(() -> documents.updateStatus(document, DocumentStatus.FAILED, "Separation failed"));

        inTransaction(() -> extraction.onExhausted(inboxMessage(document), "late"));

        assertThat(documents.get(document).failureReason()).isEqualTo("Separation failed");
        then(completion).should(never()).onDocumentFinished(any());
    }

    @Test
    void exhaustedLeavesCompletedDocumentAlone() {
        UUID document = classifiedDocument(1, 1, 1);
        extraction.extract(document);

        inTransaction(() -> extraction.onExhausted(inboxMessage(document), "late"));

        assertThat(documents.get(document).status()).isEqualTo(DocumentStatus.COMPLETED);
        then(completion).should(times(1)).onDocumentFinished(document);
    }

    // --- Helpers

    private void answerPasses(Supplier<ExtractionAnswer> answer) {
        willAnswer(call -> {
                    record(call.getArgument(1));
                    return answer.get();
                })
                .given(vision)
                .extract(any(), anyList(), any());
    }

    private void answerConsolidation(ConsolidatedAnswer draft) {
        willAnswer(call -> {
                    transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
                    return draft;
                })
                .given(vision)
                .consolidate(any(), any(), anyList());
    }

    private void answerFinals(Supplier<ExtractionAnswer> answer) {
        willAnswer(call -> {
                    record(call.getArgument(1));
                    draftsSent.add(call.getArgument(3));
                    validationErrorsSent.add(List.copyOf(call.<List<String>>getArgument(4)));
                    return answer.get();
                })
                .given(vision)
                .finalizeExtraction(any(), anyList(), any(), any(), anyList());
    }

    private void record(List<PageImageRef> sent) {
        transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
        pagesSent.add(sent.stream().map(PageImageRef::pageNumber).toList());
    }

    /** Every field the Pay stubs schema requires, null except the employer. */
    private JsonNode validData(String employer) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        schema.get("required").forEach(field -> data.putNull(field.asString()));
        data.put("employerName", employer);
        return data;
    }

    private JsonNode invalidData() {
        ObjectNode data = (ObjectNode) validData("Acme");
        data.remove("netPay");
        return data;
    }

    /** A Pay stubs document covering {@code start}–{@code end} of a file of {@code pageCount} recorded pages. */
    private UUID classifiedDocument(int pageCount, int start, int end) {
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
            UUID document = documents
                    .createAll(file, List.of(new PageRange(start, end)))
                    .getFirst()
                    .id();
            documents.assignType(document, payStubs);
            documents.updateStatus(document, DocumentStatus.CLASSIFIED, null);
            return document;
        });
    }

    private InboxMessageRecord inboxMessage(UUID document) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.inbox_message
                            (id, message_type, aggregate_id, payload, status, attempts, next_attempt_at, created_at)
                        VALUES (?, 'EXTRACT_DOCUMENT', ?, CAST(? AS jsonb), 'PROCESSING', 1, now(), now())
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
