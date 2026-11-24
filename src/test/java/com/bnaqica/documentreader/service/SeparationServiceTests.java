package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.config.ConversionConfig;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.dto.CallContext;
import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.SeparationAnswer;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import com.bnaqica.documentreader.exception.VisionException;
import com.bnaqica.documentreader.processors.BoundaryVoteProcessor;
import com.bnaqica.documentreader.processors.ConversionFixtures;
import com.bnaqica.documentreader.processors.DetectedType;
import com.bnaqica.documentreader.processors.ImageConversionProcessor;
import com.bnaqica.documentreader.processors.S3Keys;
import com.bnaqica.documentreader.publisher.OutboxPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    ClockConfig.class,
    ConversionConfig.class,
    SeparationServiceTests.StorageConfig.class,
    SeparationServiceImpl.class,
    BoundaryVoteProcessor.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class,
    SourceFileServiceImpl.class,
    SourceFilePageServiceImpl.class,
    SeparationRunServiceImpl.class,
    DocumentServiceImpl.class,
    DocumentTypeServiceImpl.class,
    InboxMessageServiceImpl.class,
    OutboxPublisher.class,
    OutboxMessageServiceImpl.class,
    WorkerSignalServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@EnableConfigurationProperties(DocumentReaderProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeparationServiceTests {

    private static final String SUBJECT = "separation-tests";

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfig {

        @Bean
        RecordingStorage recordingStorage() {
            return new RecordingStorage();
        }

        @Bean
        S3Keys s3Keys(DocumentReaderProperties properties) {
            return new S3Keys(properties.s3().prefix());
        }
    }

    @Autowired
    private SeparationServiceImpl separation;

    @Autowired
    private BatchService batches;

    @Autowired
    private SourceFileService sourceFiles;

    @Autowired
    private DocumentService documents;

    @Autowired
    private SeparationRunService separationRuns;

    @Autowired
    private SourceFilePageService pages;

    @Autowired
    private InboxMessageService inbox;

    @Autowired
    private RecordingStorage storage;

    @Autowired
    private S3Keys keys;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private VisionClient vision;

    @MockitoBean
    private BatchCompletionService completion;

    @MockitoSpyBean
    private ImageConversionProcessor conversion;

    /** Each call's window (first and last page sent) and whether a transaction was open. */
    private final Queue<List<Integer>> calls = new ConcurrentLinkedQueue<>();

    private final Queue<Boolean> transactionActive = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void recordCalls() {
        answerEveryCall(window -> new SeparationAnswer(List.of(1), List.of("Document")));
    }

    @AfterEach
    void cleanUp() {
        ProcessingFixtures fixtures = new ProcessingFixtures(jdbc);
        for (UUID batch : jdbc.sql("SELECT id FROM document_reader.batch WHERE requested_by = ?")
                .param(SUBJECT)
                .query(UUID.class)
                .list()) {
            jdbc.sql("""
                            DELETE FROM document_reader.outbox_message WHERE aggregate_id IN
                              (SELECT id FROM document_reader.document WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            jdbc.sql("""
                            DELETE FROM document_reader.inbox_message WHERE aggregate_id IN
                              (SELECT id FROM document_reader.source_file WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            jdbc.sql("""
                            DELETE FROM document_reader.source_file_page WHERE source_file_id IN
                              (SELECT id FROM document_reader.source_file WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            fixtures.deleteBatch(batch);
        }
        storage.reset();
    }

    // --- Separating

    @Test
    void singlePageSkipsModel() {
        UUID file = storedFile(ConversionFixtures.png(40, 30), DetectedType.PNG);

        separation.separate(file);

        then(vision).shouldHaveNoInteractions();
        assertThat(documents.findBySourceFile(file))
                .singleElement()
                .satisfies(doc ->
                        assertThat(List.of(doc.startPage(), doc.endPage())).containsExactly(1, 1));
        assertThat(sourceFiles.get(file).status()).isEqualTo(SourceFileStatus.SEPARATED);
        assertThat(classifyTargets(file)).hasSize(1);
        assertThat(pages.findBySourceFile(file)).hasSize(1);
        assertThat(storage.exists(keys.page(file, 1))).isTrue();
    }

    @Test
    void savesRunsCreatesDocumentsMarksSeparatedAndPublishesOneClassifyPerDocument() {
        UUID file = storedPdf(6);
        AtomicInteger run = new AtomicInteger();
        List<List<Integer>> answers = List.of(List.of(1, 3), List.of(1, 3, 5), List.of(1));
        answerEveryCall(window -> new SeparationAnswer(answers.get(run.getAndIncrement()), List.of("A", "B", "C")));

        separation.separate(file);

        assertThat(separationRuns.findBySourceFile(file)).hasSize(3).allSatisfy(saved -> {
            assertThat(saved.succeeded()).isTrue();
            assertThat(List.of(saved.windowStart(), saved.windowEnd())).containsExactly(1, 6);
        });
        List<DocumentRecord> created = documents.findBySourceFile(file);
        assertThat(created)
                .extracting(DocumentRecord::sequence, DocumentRecord::startPage, DocumentRecord::endPage)
                .containsExactly(tuple(1, 1, 2), tuple(2, 3, 6));
        assertThat(sourceFiles.get(file).status()).isEqualTo(SourceFileStatus.SEPARATED);
        assertThat(classifyTargets(file))
                .containsExactlyInAnyOrderElementsOf(
                        created.stream().map(DocumentRecord::id).toList());
        assertThat(calls).hasSize(3).allSatisfy(window -> assertThat(window).containsExactly(1, 6));
    }

    @Test
    void callsRunOutsideTransactions() {
        separation.separate(storedPdf(3));

        assertThat(transactionActive).hasSize(3).containsOnly(false);
    }

    // --- Retries

    @Test
    void convertsOnceAndReusesPagesOnRetry() {
        UUID file = storedPdf(3);
        answerEveryCall(window -> {
            throw failure();
        });
        assertThatThrownBy(() -> separation.separate(file)).isInstanceOf(IllegalStateException.class);
        answerEveryCall(window -> new SeparationAnswer(List.of(1), List.of("A")));

        separation.separate(file);

        then(conversion).should(times(1)).forEachPngPage(any(), any(), any());
        assertThat(sourceFiles.get(file).status()).isEqualTo(SourceFileStatus.SEPARATED);
    }

    @Test
    void retryReusesSuccessfulRuns() {
        UUID file = storedPdf(3);
        AtomicInteger call = new AtomicInteger();
        // First attempt: run 1 succeeds, runs 2 and 3 fail
        answerEveryCall(window -> {
            if (call.getAndIncrement() == 0) {
                return new SeparationAnswer(List.of(1, 2), List.of("A", "B"));
            }
            throw failure();
        });
        assertThatThrownBy(() -> separation.separate(file)).isInstanceOf(IllegalStateException.class);
        calls.clear();
        answerEveryCall(window -> new SeparationAnswer(List.of(1, 2), List.of("A", "B")));

        separation.separate(file);

        assertThat(calls).hasSize(2); // 3 runs needed, 1 already succeeded
        assertThat(separationRuns.findBySourceFile(file)).hasSize(5);
        assertThat(documents.findBySourceFile(file)).hasSize(2);
    }

    @Test
    void lessThanMajorityOfRunsSucceedingThrows() {
        UUID file = storedPdf(3);
        AtomicInteger call = new AtomicInteger();
        answerEveryCall(window -> {
            if (call.getAndIncrement() == 0) {
                return new SeparationAnswer(List.of(1), List.of("A"));
            }
            throw failure();
        });

        assertThatThrownBy(() -> separation.separate(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too few successful separation runs");
        assertThat(documents.findBySourceFile(file)).isEmpty();
        assertThat(sourceFiles.get(file).status()).isEqualTo(SourceFileStatus.SEPARATING);
        assertThat(separationRuns.findBySourceFile(file))
                .extracting(saved -> saved.succeeded())
                .containsExactlyInAnyOrder(true, false, false);
    }

    @Test
    void longFileIsSentInWindows() {
        UUID file = storedPdf(20);
        // Answers are in window numbering: window 14-20's page 3 is the file's page 16
        answerEveryCall(window -> window.getFirst() == 1
                ? new SeparationAnswer(List.of(1, 5), List.of("A", "B"))
                : new SeparationAnswer(List.of(1, 3), List.of("B", "C")));

        separation.separate(file);

        assertThat(calls).hasSize(6).containsOnly(List.of(1, 15), List.of(14, 20));
        assertThat(documents.findBySourceFile(file))
                .extracting(DocumentRecord::startPage, DocumentRecord::endPage)
                .containsExactly(tuple(1, 4), tuple(5, 15), tuple(16, 20));
        assertThat(separationRuns.findBySourceFile(file))
                .filteredOn(saved -> saved.windowStart() == 14)
                .allSatisfy(saved -> assertThat(saved.boundaries())
                        .extracting(boundary -> boundary.startPage())
                        .containsExactly(14, 16));
    }

    @Test
    void fileFailedWhileSeparatingGetsNoDocuments() {
        UUID file = storedPdf(3);
        // Another attempt exhausts the message while this one is still calling the model
        AtomicInteger call = new AtomicInteger();
        answerEveryCall(window -> {
            if (call.getAndIncrement() == 0) {
                inTransaction(() -> sourceFiles.updateStatus(file, SourceFileStatus.FAILED, "Separation failed"));
            }
            return new SeparationAnswer(List.of(1, 2), List.of("A", "B"));
        });

        separation.separate(file);

        assertThat(documents.findBySourceFile(file)).isEmpty();
        assertThat(classifyTargets(file)).isEmpty();
        SourceFileRecord failed = sourceFiles.get(file);
        assertThat(failed.status()).isEqualTo(SourceFileStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("Separation failed");
    }

    @Test
    void pageCountThatDisagreesWithConversionThrows() {
        UUID batch = processingBatch(1);
        // Intake counted 4 pages, but the stored PDF renders 3
        List<SourceFileRecord> files = committed(() -> sourceFiles.createAll(
                batch, List.of(new NewSourceFile("file.bin", "application/pdf", 10, "a".repeat(64), 4))));
        UUID file = files.getFirst().id();
        String key = keys.original(file, "pdf");
        storage.put(key, ConversionFixtures.pdf(3), "application/pdf");
        inTransaction(() -> sourceFiles.markStored(file, "test-bucket", key));

        assertThatThrownBy(() -> separation.separate(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("3 pages")
                .hasMessageContaining("4");
        then(vision).shouldHaveNoInteractions();
    }

    @Test
    void filesOfOneBatchSeparateIndependently() {
        UUID batch = processingBatch(2);
        List<SourceFileRecord> files = storeFiles(batch, ConversionFixtures.pdf(3), ConversionFixtures.pdf(2));
        answerEveryCall(window -> new SeparationAnswer(List.of(1, 2), List.of("A", "B")));

        separation.separate(files.get(0).id());
        separation.separate(files.get(1).id());

        for (SourceFileRecord file : files) {
            assertThat(documents.findBySourceFile(file.id()))
                    .allSatisfy(doc -> assertThat(doc.sourceFileId()).isEqualTo(file.id()))
                    .extracting(DocumentRecord::sequence)
                    .containsExactly(1, 2);
        }
    }

    @Test
    void idempotentWhenDocumentsAlreadyExist() {
        UUID file = storedPdf(3);
        separation.separate(file);
        calls.clear();
        int documentsBefore = documents.findBySourceFile(file).size();
        int messagesBefore = classifyTargets(file).size();

        separation.separate(file);

        assertThat(calls).isEmpty();
        assertThat(documents.findBySourceFile(file)).hasSize(documentsBefore);
        assertThat(classifyTargets(file)).hasSize(messagesBefore);
    }

    // --- Inbox handler

    @Test
    void handleSeparatesAndMarksMessageDone() {
        UUID file = storedPdf(1);
        InboxMessageRecord message = inboxMessage(file);

        assertThat(separation.type()).isEqualTo(MessageType.SEPARATE_FILE);
        separation.handle(message);

        assertThat(sourceFiles.get(file).status()).isEqualTo(SourceFileStatus.SEPARATED);
        assertThat(inbox.get(message.id()).status().name()).isEqualTo("DONE");
    }

    @Test
    void exhaustedFailsFileAndRunsCompletionCheck() {
        UUID file = storedPdf(3);
        inTransaction(() -> sourceFiles.updateStatus(file, SourceFileStatus.SEPARATING, null));

        inTransaction(() -> separation.onExhausted(inboxMessage(file), "VisionException: timeout"));

        SourceFileRecord failed = sourceFiles.get(file);
        assertThat(failed.status()).isEqualTo(SourceFileStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("Separation failed");
        then(completion).should().onFileFailed(file);
    }

    @Test
    void exhaustedLeavesSeparatedFileAlone() {
        UUID file = storedPdf(1);
        separation.separate(file);

        inTransaction(() -> separation.onExhausted(inboxMessage(file), "late"));

        assertThat(sourceFiles.get(file).status()).isEqualTo(SourceFileStatus.SEPARATED);
        then(completion).should(never()).onFileFailed(any());
    }

    // --- Helpers

    private void answerEveryCall(Function<List<Integer>, SeparationAnswer> answer) {
        given(vision.separate(any(CallContext.class), anyList(), anyList())).willAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            List<PageImageRef> sent = invocation.getArgument(1);
            List<Integer> window =
                    List.of(sent.getFirst().pageNumber(), sent.getLast().pageNumber());
            calls.add(window);
            return answer.apply(window);
        });
    }

    private static VisionException failure() {
        return new VisionException(OpenAiCallPurpose.SEPARATE, "timeout", null, null);
    }

    private UUID storedPdf(int pageCount) {
        return storedFile(ConversionFixtures.pdf(pageCount), DetectedType.PDF);
    }

    private UUID storedFile(byte[] bytes, DetectedType type) {
        return storeFiles(processingBatch(1), type, bytes).getFirst().id();
    }

    private List<SourceFileRecord> storeFiles(UUID batch, byte[]... pdfs) {
        return storeFiles(batch, DetectedType.PDF, pdfs);
    }

    /** Stored files of one type, with their originals in the storage fake, as intake leaves them. */
    private List<SourceFileRecord> storeFiles(UUID batch, DetectedType type, byte[]... contents) {
        ImageConversionProcessor counter = new ImageConversionProcessor(200, 2048, null);
        List<NewSourceFile> files = new ArrayList<>();
        for (byte[] bytes : contents) {
            files.add(new NewSourceFile(
                    "file.bin", type.mimeType(), bytes.length, "a".repeat(64), counter.pageCount(bytes, type)));
        }
        List<SourceFileRecord> created = committed(() -> sourceFiles.createAll(batch, files));
        for (int i = 0; i < created.size(); i++) {
            UUID id = created.get(i).id();
            String key = keys.original(id, type.extension());
            storage.put(key, contents[i], type.mimeType());
            inTransaction(() -> sourceFiles.markStored(id, "test-bucket", key));
        }
        return sourceFiles.findByBatch(batch);
    }

    private UUID processingBatch(int fileCount) {
        return committed(() -> {
            UUID id = batches.create(new NewBatch("CAR_REFINANCE", false, null, SUBJECT, fileCount))
                    .id();
            batches.updateStatus(id, BatchStatus.PROCESSING, null);
            return id;
        });
    }

    private List<UUID> classifyTargets(UUID file) {
        return jdbc.sql("""
                        SELECT aggregate_id FROM document_reader.outbox_message
                        WHERE message_type = 'CLASSIFY_DOCUMENT'
                          AND aggregate_id IN (SELECT id FROM document_reader.document WHERE source_file_id = ?)
                        """).param(file).query(UUID.class).list();
    }

    private InboxMessageRecord inboxMessage(UUID file) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.inbox_message
                            (id, message_type, aggregate_id, payload, status, attempts, next_attempt_at, created_at)
                        VALUES (?, 'SEPARATE_FILE', ?, CAST(? AS jsonb), 'PROCESSING', 1, now(), now())
                        """).params(id, file, "{\"sourceFileId\":\"" + file + "\"}").update();
        return inbox.get(id);
    }

    private <T> T committed(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }
}
