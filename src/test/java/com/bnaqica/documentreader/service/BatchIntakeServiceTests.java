package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.config.ConversionConfig;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.request.BatchUploadRequest;
import com.bnaqica.documentreader.dto.response.BatchResultResponse;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.exception.BatchValidationException;
import com.bnaqica.documentreader.exception.BatchValidationException.FileError;
import com.bnaqica.documentreader.exception.InvalidWebhookUrlException;
import com.bnaqica.documentreader.processors.ContentTypeDetector;
import com.bnaqica.documentreader.processors.ConversionFixtures;
import com.bnaqica.documentreader.processors.S3Keys;
import com.bnaqica.documentreader.processors.WebhookUrlValidator;
import com.bnaqica.documentreader.publisher.OutboxPublisher;
import com.bnaqica.documentreader.service.BatchIntakeService.Accepted;
import com.bnaqica.documentreader.service.BatchIntakeService.Finished;
import com.bnaqica.documentreader.service.BatchIntakeService.IntakeOutcome;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@DataJpaTest(
        properties = {
            "app.document-reader.file.max-size=1MB",
            "app.document-reader.batch.max-total-size=3MB",
            "app.document-reader.wait.timeout=5s"
        })
@Import({
    TestcontainersConfiguration.class,
    ClockConfig.class,
    ConversionConfig.class,
    BatchIntakeServiceTests.StorageConfig.class,
    BatchIntakeServiceImpl.class,
    BatchCompletionServiceImpl.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class,
    SourceFileServiceImpl.class,
    DocumentServiceImpl.class,
    DocumentTypeServiceImpl.class,
    OutboxPublisher.class,
    OutboxMessageServiceImpl.class,
    WorkerSignalServiceImpl.class,
    ContentTypeDetector.class,
    WebhookUrlValidator.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@EnableConfigurationProperties(DocumentReaderProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BatchIntakeServiceTests {

    private static final String VALID_CODES = "loanPurpose must be one of: BOAT_PURCHASE, BOAT_REFINANCE, "
            + "CAR_PURCHASE, CAR_REFINANCE, HOME_PURCHASE, MORTGAGE_REFINANCE, RV_PURCHASE, RV_REFINANCE";

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
    private BatchIntakeService intake;

    @Autowired
    private RecordingStorage storage;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private CompletionWaitService completionWait;

    @MockitoBean
    private BatchQueryService query;

    private final String subject = "intake-" + UUID.randomUUID();
    private final Caller caller = new Caller(subject, false);

    @AfterEach
    void cleanUp() {
        List<UUID> batches = jdbc.sql("SELECT id FROM document_reader.batch WHERE requested_by = ?")
                .param(subject)
                .query(UUID.class)
                .list();
        for (UUID batch : batches) {
            jdbc.sql("""
                            DELETE FROM document_reader.outbox_message WHERE aggregate_id = :batch
                               OR aggregate_id IN (SELECT id FROM document_reader.source_file WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            new ProcessingFixtures(jdbc).deleteBatch(batch);
        }
        storage.reset();
    }

    // --- Request fields

    @Test
    void missingLoanPurposeIs400ListingValidCodes() {
        assertRefused(request(null, false, null, pdf("a.pdf")), HttpStatus.BAD_REQUEST, VALID_CODES);
    }

    @Test
    void unknownLoanPurposeIs400() {
        assertRefused(request("SPACESHIP_PURCHASE", false, null, pdf("a.pdf")), HttpStatus.BAD_REQUEST, VALID_CODES);
    }

    @Test
    void noFilesIs400() {
        assertRefused(
                new BatchUploadRequest(List.of(), "CAR_REFINANCE", "false", null),
                HttpStatus.BAD_REQUEST,
                "Send between 1 and 20 files");
        assertRefused(
                new BatchUploadRequest(null, "CAR_REFINANCE", "false", null),
                HttpStatus.BAD_REQUEST,
                "Send between 1 and 20 files");
    }

    @Test
    void twentyOneFilesIs400() {
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            files.add(pdf("f" + i + ".pdf"));
        }
        assertRefused(
                new BatchUploadRequest(files, "CAR_REFINANCE", "false", null),
                HttpStatus.BAD_REQUEST,
                "Send between 1 and 20 files");
    }

    @Test
    void asyncTrueWithoutUrlIs400() {
        assertRefused(
                request("CAR_REFINANCE", true, null, pdf("a.pdf")),
                HttpStatus.BAD_REQUEST,
                "webhookUrl is required when async is true");
        assertRefused(
                request("CAR_REFINANCE", true, " ", pdf("a.pdf")),
                HttpStatus.BAD_REQUEST,
                "webhookUrl is required when async is true");
    }

    @Test
    void urlWithoutAsyncIs400() {
        assertRefused(
                request("CAR_REFINANCE", false, "https://example.com/hook", pdf("a.pdf")),
                HttpStatus.BAD_REQUEST,
                "webhookUrl is only used when async is true");
        assertRefused(
                request("CAR_REFINANCE", null, "https://example.com/hook", pdf("a.pdf")),
                HttpStatus.BAD_REQUEST,
                "webhookUrl is only used when async is true");
    }

    @Test
    void asyncThatIsNotABooleanIs400() {
        assertRefused(
                new BatchUploadRequest(List.of(pdf("a.pdf")), "CAR_REFINANCE", "maybe", null),
                HttpStatus.BAD_REQUEST,
                "async must be true or false");
    }

    @Test
    void asyncIgnoresCase() {
        IntakeOutcome outcome = intake.submit(
                new BatchUploadRequest(List.of(pdf("a.pdf")), "CAR_REFINANCE", "TRUE", "https://example.com/hook"),
                caller);

        assertThat(outcome).isInstanceOf(Accepted.class);
        then(completionWait).should(never()).await(any(), any());
    }

    @Test
    void relativeWebhookUrlIs400() {
        assertThatThrownBy(() -> intake.submit(request("CAR_REFINANCE", true, "/hooks/done", pdf("a.pdf")), caller))
                .isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessage("webhookUrl must be an absolute http or https URL");
        assertThat(batchCount()).isZero();
    }

    @Test
    void ftpWebhookUrlIs400() {
        assertThatThrownBy(() ->
                        intake.submit(request("CAR_REFINANCE", true, "ftp://example.com/hook", pdf("a.pdf")), caller))
                .isInstanceOf(InvalidWebhookUrlException.class);
    }

    @Test
    void httpAndHttpsWebhookUrlsAreAccepted() {
        intake.submit(request("CAR_REFINANCE", true, "https://example.com/hook", pdf("a.pdf")), caller);
        intake.submit(request("CAR_REFINANCE", true, "http://localhost:9000/hook", pdf("b.pdf")), caller);

        assertThat(jdbc.sql("SELECT webhook_url FROM document_reader.batch WHERE requested_by = ? ORDER BY webhook_url")
                        .param(subject)
                        .query(String.class)
                        .list())
                .containsExactly("http://localhost:9000/hook", "https://example.com/hook");
    }

    // --- Files

    @Test
    void batchOver200MbIs413() {
        // Lowered limits: 3 MB per batch, 1 MB per file
        byte[] almostOneMegabyte = padded(ConversionFixtures.pdf(1), 900 * 1024);
        assertRefused(
                request(
                        "CAR_REFINANCE",
                        false,
                        null,
                        file("a.pdf", "application/pdf", almostOneMegabyte),
                        file("b.pdf", "application/pdf", almostOneMegabyte),
                        file("c.pdf", "application/pdf", almostOneMegabyte),
                        file("d.pdf", "application/pdf", almostOneMegabyte)),
                HttpStatus.CONTENT_TOO_LARGE,
                "The files total 4 MB; the limit is 3 MB");
    }

    @Test
    void docxIs415NamingTheFile() {
        BatchValidationException refused = refused(request(
                "CAR_REFINANCE",
                false,
                null,
                pdf("a.pdf"),
                file("notes.docx", "application/octet-stream", ConversionFixtures.docx())));

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(refused.getMessage()).isEqualTo("1 file can't be accepted");
        assertThat(refused.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.index()).isEqualTo(1);
            assertThat(error.fileName()).isEqualTo("notes.docx");
            assertThat(error.status()).isEqualTo(415);
            assertThat(error.detail()).startsWith("Unsupported content type");
        });
    }

    @Test
    void fileOver50MbIs413() {
        // Lowered limit: 1 MB per file
        BatchValidationException refused = refused(request(
                "CAR_REFINANCE",
                false,
                null,
                file("big.pdf", "application/pdf", padded(ConversionFixtures.pdf(1), 1024 * 1024 + 1))));

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(refused.getErrors())
                .singleElement()
                .isEqualTo(new FileError(0, "big.pdf", 413, "The file is larger than 1 MB"));
    }

    @Test
    void fileOver100PagesIs422() {
        BatchValidationException refused = refused(request(
                "CAR_REFINANCE", false, null, file("long.pdf", "application/pdf", ConversionFixtures.pdf(101))));

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getErrors())
                .singleElement()
                .isEqualTo(new FileError(0, "long.pdf", 422, "The file has 101 pages; the limit is 100"));
    }

    @Test
    void severalFailuresUseFirstStatusInCheckOrder() {
        BatchValidationException refused = refused(request(
                "CAR_REFINANCE",
                false,
                null,
                file("long.pdf", "application/pdf", ConversionFixtures.pdf(101)),
                file("notes.docx", "application/octet-stream", ConversionFixtures.docx()),
                pdf("fine.pdf")));

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(refused.getMessage()).isEqualTo("2 files can't be accepted");
        assertThat(refused.getErrors())
                .extracting(FileError::index, FileError::status)
                .containsExactly(tuple(0, 422), tuple(1, 415));
    }

    @Test
    void oneEncryptedPdfAmongValidFilesIs422AndStoresNothing() {
        int batchesBefore = count("batch");
        int filesBefore = count("source_file");
        int outboxBefore = count("outbox_message");

        BatchValidationException refused = refused(request(
                "CAR_REFINANCE",
                false,
                null,
                pdf("a.pdf"),
                file("photo.png", "image/png", ConversionFixtures.png(40, 30)),
                file("locked.pdf", "application/pdf", ConversionFixtures.passwordProtectedPdf())));

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.index()).isEqualTo(2);
            assertThat(error.fileName()).isEqualTo("locked.pdf");
            assertThat(error.status()).isEqualTo(422);
        });
        assertThat(count("batch")).isEqualTo(batchesBefore);
        assertThat(count("source_file")).isEqualTo(filesBefore);
        assertThat(count("outbox_message")).isEqualTo(outboxBefore);
        assertThat(storage.objects()).isEmpty();
    }

    // --- Storing and queueing

    @Test
    void intakeOrdersWrites() {
        List<String> statusesAtFirstPut = new ArrayList<>();
        storage.onPut(key -> {
            if (statusesAtFirstPut.isEmpty()) {
                statusesAtFirstPut.addAll(
                        jdbc.sql("""
                                SELECT b.status || '/' || f.status FROM document_reader.source_file f
                                JOIN document_reader.batch b ON b.id = f.batch_id
                                WHERE b.requested_by = ? ORDER BY f.file_index
                                """).param(subject).query(String.class).list());
            }
        });

        intake.submit(request("CAR_REFINANCE", false, null, pdf("a.pdf"), pdf("b.pdf"), pdf("c.pdf")), caller);

        assertThat(statusesAtFirstPut).containsExactly("RECEIVED/RECEIVED", "RECEIVED/RECEIVED", "RECEIVED/RECEIVED");
        UUID batchId = onlyBatch();
        assertThat(batchStatus(batchId)).isEqualTo("PROCESSING");
        List<Map<String, Object>> files = jdbc.sql("""
                        SELECT id, status, s3_bucket, s3_key FROM document_reader.source_file
                        WHERE batch_id = ? ORDER BY file_index
                        """).param(batchId).query().listOfRows();
        assertThat(files).hasSize(3).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("STORED");
            assertThat(row.get("s3_bucket")).isEqualTo("test-bucket");
            assertThat(row.get("s3_key")).isEqualTo("files/" + row.get("id") + "/original.pdf");
            assertThat(storage.objects()).containsKey((String) row.get("s3_key"));
        });
        List<UUID> separateFileTargets =
                jdbc.sql("""
                        SELECT aggregate_id FROM document_reader.outbox_message
                        WHERE message_type = 'SEPARATE_FILE'
                          AND aggregate_id IN (SELECT id FROM document_reader.source_file WHERE batch_id = ?)
                        """).param(batchId).query(UUID.class).list();
        assertThat(separateFileTargets)
                .containsExactlyInAnyOrderElementsOf(
                        files.stream().map(row -> (UUID) row.get("id")).toList());
    }

    @Test
    void detectedTypeIsStoredAndNamesTheKey() {
        intake.submit(
                request(
                        "CAR_REFINANCE",
                        false,
                        null,
                        file("photo", "application/octet-stream", ConversionFixtures.png(40, 30)),
                        file("scan.final.JPEG", "image/jpeg", ConversionFixtures.markerJpeg(1))),
                caller);

        List<Map<String, Object>> files =
                jdbc.sql("""
                        SELECT content_type, s3_key FROM document_reader.source_file
                        WHERE batch_id = ? ORDER BY file_index
                        """).param(onlyBatch()).query().listOfRows();
        assertThat(files.get(0).get("content_type")).isEqualTo("image/png");
        assertThat((String) files.get(0).get("s3_key")).endsWith("/original.png");
        assertThat(storage.contentType((String) files.get(0).get("s3_key"))).isEqualTo("image/png");
        assertThat(files.get(1).get("content_type")).isEqualTo("image/jpeg");
        assertThat((String) files.get(1).get("s3_key")).endsWith("/original.jpg");
    }

    @Test
    void batchRecordsCallerAndFileDetails() {
        byte[] twoPages = ConversionFixtures.pdf(2);

        intake.submit(
                request(
                        "HOME_PURCHASE",
                        true,
                        "https://example.com/hook",
                        file("pack.pdf", "application/pdf", twoPages)),
                caller);

        Map<String, Object> batch = jdbc.sql("""
                        SELECT loan_purpose_code, async, webhook_url, file_count, requested_by
                        FROM document_reader.batch WHERE id = ?
                        """).param(onlyBatch()).query().singleRow();
        assertThat(batch)
                .containsEntry("loan_purpose_code", "HOME_PURCHASE")
                .containsEntry("async", true)
                .containsEntry("webhook_url", "https://example.com/hook")
                .containsEntry("file_count", 1)
                .containsEntry("requested_by", subject);
        Map<String, Object> file = jdbc.sql("""
                        SELECT file_index, original_filename, size_bytes, sha256, page_count
                        FROM document_reader.source_file WHERE batch_id = ?
                        """).param(onlyBatch()).query().singleRow();
        assertThat(file)
                .containsEntry("file_index", 0)
                .containsEntry("original_filename", "pack.pdf")
                .containsEntry("size_bytes", (long) twoPages.length)
                .containsEntry("sha256", sha256(twoPages))
                .containsEntry("page_count", 2);
    }

    @Test
    void s3FailureMarksBatchFailed() {
        storage.failEveryPut();
        given(completionWait.await(any(), any())).willReturn(true);
        BatchResultResponse failedResult =
                BatchResultResponse.builder().status(BatchStatus.FAILED).build();
        given(query.getResult(any(), eq(caller))).willReturn(failedResult);

        IntakeOutcome outcome =
                intake.submit(request("CAR_REFINANCE", false, null, pdf("a.pdf"), pdf("b.pdf")), caller);

        UUID batchId = onlyBatch();
        assertThat(outcome).isEqualTo(new Finished(failedResult));
        assertThat(jdbc.sql("SELECT status, failure_reason FROM document_reader.batch WHERE id = ?")
                        .param(batchId)
                        .query()
                        .singleRow())
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "Storing the files failed");
        assertThat(jdbc.sql(
                                "SELECT status || '/' || failure_reason FROM document_reader.source_file WHERE batch_id = ?")
                        .param(batchId)
                        .query(String.class)
                        .list())
                .containsExactly("FAILED/Storing the files failed", "FAILED/Storing the files failed");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM document_reader.outbox_message WHERE message_type = 'SEPARATE_FILE'
                          AND aggregate_id IN (SELECT id FROM document_reader.source_file WHERE batch_id = ?)
                        """).param(batchId).query(Integer.class).single()).isZero();
        then(completionWait).should().notifyBatchCompleted(batchId);
        then(query).should().getResult(batchId, caller);
    }

    @Test
    void unexpectedStorageFailureAlsoFailsTheBatch() {
        storage.failEveryPutWith(new IllegalStateException("not an S3 error"));

        IntakeOutcome outcome =
                intake.submit(request("CAR_REFINANCE", true, "https://example.com/hook", pdf("a.pdf")), caller);

        assertThat(outcome).isEqualTo(new Accepted(onlyBatch(), BatchStatus.FAILED));
        assertThat(batchStatus(onlyBatch())).isEqualTo("FAILED");
    }

    @Test
    void emptyWebhookUrlWithoutAsyncIsIgnored() {
        intake.submit(request("CAR_REFINANCE", null, "", pdf("a.pdf")), caller);

        assertThat(jdbc.sql("SELECT webhook_url FROM document_reader.batch WHERE id = ?")
                        .param(onlyBatch())
                        .query(String.class)
                        .optional())
                .isEmpty();
    }

    @Test
    void callerWithoutSubjectIsRefusedBeforeAnyWork() {
        int batchesBefore = count("batch");

        assertThatThrownBy(() ->
                        intake.submit(request("CAR_REFINANCE", false, null, pdf("a.pdf")), new Caller(null, false)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(count("batch")).isEqualTo(batchesBefore);
        assertThat(storage.objects()).isEmpty();
    }

    // --- Outcomes

    @Test
    void asyncReturnsAcceptedAtOnce() {
        IntakeOutcome outcome =
                intake.submit(request("CAR_REFINANCE", true, "https://example.com/hook", pdf("a.pdf")), caller);

        assertThat(outcome).isEqualTo(new Accepted(onlyBatch(), BatchStatus.PROCESSING));
        then(completionWait).should(never()).await(any(), any());
        then(query).shouldHaveNoInteractions();
    }

    @Test
    void syncReturnsFinishedWhenCompletedInTime() {
        List<Boolean> transactionActive = new ArrayList<>();
        given(completionWait.await(any(), eq(Duration.ofSeconds(5)))).willAnswer(call -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        BatchResultResponse completed =
                BatchResultResponse.builder().status(BatchStatus.COMPLETED).build();
        given(query.getResult(any(), eq(caller))).willReturn(completed);

        IntakeOutcome outcome = intake.submit(request("CAR_REFINANCE", false, null, pdf("a.pdf")), caller);

        assertThat(outcome).isEqualTo(new Finished(completed));
        assertThat(transactionActive).containsExactly(false);
        then(completionWait).should().await(onlyBatch(), Duration.ofSeconds(5));
        then(query).should().getResult(onlyBatch(), caller);
    }

    @Test
    void syncReturnsAcceptedOnTimeout() {
        List<Boolean> transactionActive = new ArrayList<>();
        given(completionWait.await(any(), any())).willAnswer(call -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return false;
        });

        IntakeOutcome outcome = intake.submit(request("CAR_REFINANCE", false, null, pdf("a.pdf")), caller);

        assertThat(outcome).isEqualTo(new Accepted(onlyBatch(), BatchStatus.PROCESSING));
        assertThat(transactionActive).containsExactly(false);
        then(query).shouldHaveNoInteractions();
    }

    // --- Helpers

    private UUID onlyBatch() {
        return jdbc.sql("SELECT id FROM document_reader.batch WHERE requested_by = ?")
                .param(subject)
                .query(UUID.class)
                .single();
    }

    private String batchStatus(UUID batchId) {
        return jdbc.sql("SELECT status FROM document_reader.batch WHERE id = ?")
                .param(batchId)
                .query(String.class)
                .single();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private BatchValidationException refused(BatchUploadRequest request) {
        try {
            intake.submit(request, caller);
        } catch (BatchValidationException e) {
            assertThat(batchCount()).isZero();
            assertThat(storage.objects()).isEmpty();
            return e;
        }
        throw new AssertionError("Expected the batch to be refused");
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM document_reader." + table)
                .query(Integer.class)
                .single();
    }

    /** The bytes, followed by zeros up to {@code size}; detection reads only the start. */
    private static byte[] padded(byte[] bytes, int size) {
        return Arrays.copyOf(bytes, size);
    }

    private void assertRefused(BatchUploadRequest request, HttpStatus status, String detail) {
        assertThatThrownBy(() -> intake.submit(request, caller))
                .isInstanceOfSatisfying(BatchValidationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getMessage()).isEqualTo(detail);
                });
        assertThat(batchCount()).isZero();
        assertThat(storage.objects()).isEmpty();
    }

    private int batchCount() {
        return jdbc.sql("SELECT count(*) FROM document_reader.batch WHERE requested_by = ?")
                .param(subject)
                .query(Integer.class)
                .single();
    }

    private static BatchUploadRequest request(
            String loanPurpose, Boolean async, String webhookUrl, MultipartFile... files) {
        return new BatchUploadRequest(List.of(files), loanPurpose, async == null ? null : async.toString(), webhookUrl);
    }

    static MockMultipartFile pdf(String name) {
        return file(name, "application/pdf", ConversionFixtures.pdf(1));
    }

    static MockMultipartFile file(String name, String declaredType, byte[] bytes) {
        return new MockMultipartFile("files", name, declaredType, bytes);
    }
}
