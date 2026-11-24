package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.NewClassificationResult;
import com.bnaqica.documentreader.dto.NewClassificationRun;
import com.bnaqica.documentreader.dto.NewExtractionResult;
import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.dto.response.BatchResultResponse;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.DocumentResultResponse;
import com.bnaqica.documentreader.dto.response.DocumentResultResponse.Pages;
import com.bnaqica.documentreader.dto.response.FileResultResponse;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.ClassificationRound;
import com.bnaqica.documentreader.entity.DecidedBy;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Rows are set up through the record services inside the test's transaction, which rolls back. */
@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    ClockConfig.class,
    BatchQueryServiceImpl.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class,
    SourceFileServiceImpl.class,
    DocumentServiceImpl.class,
    DocumentTypeServiceImpl.class,
    ClassificationRunServiceImpl.class,
    ClassificationResultServiceImpl.class,
    ExtractionResultServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@EnableConfigurationProperties(DocumentReaderProperties.class)
class BatchQueryServiceTests {

    private static final Caller OWNER = new Caller("owner-1", false);

    @Autowired
    private BatchQueryService query;

    @Autowired
    private BatchService batches;

    @Autowired
    private SourceFileService sourceFiles;

    @Autowired
    private DocumentService documents;

    @Autowired
    private ClassificationRunService classificationRuns;

    @Autowired
    private ClassificationResultService classificationResults;

    @Autowired
    private ExtractionResultService extractionResults;

    @Autowired
    private JdbcClient jdbc;

    private UUID payStubs;
    private UUID w2;

    @BeforeEach
    void seededTypes() {
        payStubs = type("Pay stubs");
        w2 = type("W-2 forms");
    }

    @Test
    void processingBatchDescribesItself() {
        UUID batchId = batch(2, 1);

        BatchResultResponse result = query.getResult(batchId, OWNER);

        assertThat(result.batchId()).isEqualTo(batchId);
        assertThat(result.status()).isEqualTo(BatchStatus.PROCESSING);
        assertThat(result.loanPurpose()).isEqualTo("CAR_REFINANCE");
        assertThat(result.receivedAt()).isNotNull();
        assertThat(result.completedAt()).isNull();
        assertThat(result.error()).isNull();
    }

    @Test
    void failedBatchShowsItsReasonAndTime() {
        UUID batchId = batch(1);
        batches.complete(batchId, BatchStatus.FAILED, "No document completed", Instant.parse("2026-11-20T16:01:12Z"));

        BatchResultResponse result = query.getResult(batchId, OWNER);

        assertThat(result.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(result.completedAt()).isEqualTo(Instant.parse("2026-11-20T16:01:12Z"));
        assertThat(result.error()).isEqualTo("No document completed");
    }

    @Test
    void resultListsDocumentsInFileThenPageOrder() {
        UUID batchId = batch(5, 1);
        List<SourceFileRecord> files = sourceFiles.findByBatch(batchId);
        // Created out of order: file 1 first, then file 0's later document before its earlier one
        documents.createAll(files.get(1).id(), List.of(new PageRange(1, 1)));
        documents.createAll(files.get(0).id(), List.of(new PageRange(3, 5), new PageRange(1, 2)));

        List<DocumentResultResponse> listed = query.getResult(batchId, OWNER).documents();

        assertThat(listed)
                .extracting(DocumentResultResponse::sourceFileId, DocumentResultResponse::pages)
                .containsExactly(
                        tuple(files.get(0).id(), new Pages(1, 2)),
                        tuple(files.get(0).id(), new Pages(3, 5)),
                        tuple(files.get(1).id(), new Pages(1, 1)));
    }

    @Test
    void failedFileIsListedWithNoDocuments() {
        UUID batchId = batch(3, 1);
        List<SourceFileRecord> files = sourceFiles.findByBatch(batchId);
        documents.createAll(files.get(0).id(), List.of(new PageRange(1, 1), new PageRange(2, 2), new PageRange(3, 3)));
        sourceFiles.updateStatus(files.get(1).id(), SourceFileStatus.FAILED, "Separation failed");

        List<FileResultResponse> listed = query.getResult(batchId, OWNER).files();

        assertThat(listed).hasSize(2);
        assertThat(listed.get(0))
                .isEqualTo(new FileResultResponse(
                        files.get(0).id(), 0, "file-0.pdf", 3, SourceFileStatus.STORED, 3, null));
        assertThat(listed.get(1))
                .isEqualTo(new FileResultResponse(
                        files.get(1).id(), 1, "file-1.pdf", 1, SourceFileStatus.FAILED, 0, "Separation failed"));
    }

    @Test
    void unclassifiedDocumentHasNullTypeAndClassification() {
        DocumentResultResponse document = onlyDocument(oneDocument());

        assertThat(document.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(document.documentType()).isNull();
        assertThat(document.classification()).isNull();
        assertThat(document.summary()).isNull();
        assertThat(document.data()).isNull();
        assertThat(document.truncated()).isFalse();
        assertThat(document.error()).isNull();
    }

    @Test
    void clearVoteShowsRunsVotesAndAgreement() {
        DocumentRecord document = oneDocument();
        runs(document.id(), ClassificationRound.INITIAL, payStubs, payStubs, w2, payStubs, payStubs);
        // A failed run doesn't count
        classificationRuns.save(
                document.id(),
                new NewClassificationRun(ClassificationRound.INITIAL, 6, "garbage", null, null, null, false));
        classify(document.id(), payStubs, false, DecidedBy.VOTE, "0.80");

        DocumentResultResponse result = onlyDocument(document);

        assertThat(result.documentType()).isEqualTo(new DocumentResultResponse.DocumentTypeSummary("Pay stubs", false));
        assertThat(result.classification().runs()).isEqualTo(5);
        assertThat(result.classification().votes())
                .containsExactly(Map.entry("Pay stubs", 4), Map.entry("W-2 forms", 1));
        assertThat(result.classification().agreement()).isEqualByComparingTo("0.80");
        assertThat(result.classification().tieBreak()).isNull();
    }

    @Test
    void tieBreakShowsItsRound() {
        DocumentRecord document = oneDocument();
        runs(document.id(), ClassificationRound.INITIAL, payStubs, payStubs, w2, w2);
        runs(document.id(), ClassificationRound.TIEBREAK, w2, payStubs, w2);
        classify(document.id(), w2, true, DecidedBy.TIEBREAK, "0.50");

        DocumentResultResponse.Classification classification =
                onlyDocument(document).classification();

        assertThat(classification.runs()).isEqualTo(4);
        assertThat(classification.votes()).containsEntry("Pay stubs", 2).containsEntry("W-2 forms", 2);
        assertThat(classification.tieBreak())
                .isEqualTo(new DocumentResultResponse.TieBreak(
                        3, Map.of("W-2 forms", 2, "Pay stubs", 1), DecidedBy.TIEBREAK));
    }

    @Test
    void modelCreatedTypeIsNew() {
        DocumentRecord document = oneDocument();
        UUID marineSurvey = modelType("Moon rock appraisal");
        runs(document.id(), ClassificationRound.INITIAL, marineSurvey);
        classify(document.id(), marineSurvey, false, DecidedBy.VOTE, "1.00");

        assertThat(onlyDocument(document).documentType())
                .isEqualTo(new DocumentResultResponse.DocumentTypeSummary("Moon rock appraisal", true));
    }

    @Test
    void extractedDocumentShowsSummaryAndData() {
        DocumentRecord document = oneDocument();
        runs(document.id(), ClassificationRound.INITIAL, payStubs);
        classify(document.id(), payStubs, false, DecidedBy.VOTE, "1.00");
        extractionResults.save(
                document.id(),
                new NewExtractionResult(
                        payStubs,
                        1,
                        JsonMapper.builder().build().readTree("{\"employerName\":\"Acme Corp\"}"),
                        "Bi-weekly pay stub"));
        documents.updateStatus(document.id(), DocumentStatus.COMPLETED, null);

        DocumentResultResponse result = onlyDocument(document);

        assertThat(result.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(result.summary()).isEqualTo("Bi-weekly pay stub");
        assertThat(result.data().get("employerName").asString()).isEqualTo("Acme Corp");
    }

    @Test
    void classifiedDocumentHasNoSummaryYet() {
        DocumentRecord document = oneDocument();
        runs(document.id(), ClassificationRound.INITIAL, payStubs);
        classify(document.id(), payStubs, false, DecidedBy.VOTE, "1.00");

        DocumentResultResponse result = onlyDocument(document);

        assertThat(result.documentType()).isNotNull();
        assertThat(result.summary()).isNull();
        assertThat(result.data()).isNull();
    }

    @Test
    void getOtherCallersBatchIs404() {
        UUID batchId = batch(1);
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> query.getResult(batchId, new Caller("someone-else", false)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Batch " + batchId + " was not found");
        assertThatThrownBy(() -> query.getResult(unknown, OWNER))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Batch " + unknown + " was not found");
    }

    @Test
    void adminReadsAnyBatch() {
        UUID batchId = batch(1);

        assertThat(query.getResult(batchId, new Caller("admin-1", true)).batchId())
                .isEqualTo(batchId);
    }

    // --- Helpers

    /** A stored, processing batch of {@code OWNER}, with one file per page count. */
    private UUID batch(int... pageCounts) {
        UUID batchId = batches.create(new NewBatch("CAR_REFINANCE", false, null, OWNER.subject(), pageCounts.length))
                .id();
        List<NewSourceFile> files = new ArrayList<>();
        for (int i = 0; i < pageCounts.length; i++) {
            files.add(new NewSourceFile("file-" + i + ".pdf", "application/pdf", 100, "a".repeat(64), pageCounts[i]));
        }
        for (SourceFileRecord file : sourceFiles.createAll(batchId, files)) {
            sourceFiles.markStored(file.id(), "test-bucket", "files/" + file.id() + "/original.pdf");
        }
        batches.updateStatus(batchId, BatchStatus.PROCESSING, null);
        return batchId;
    }

    private DocumentRecord oneDocument() {
        UUID batchId = batch(1);
        return documents
                .createAll(sourceFiles.findByBatch(batchId).getFirst().id(), List.of(new PageRange(1, 1)))
                .getFirst();
    }

    private DocumentResultResponse onlyDocument(DocumentRecord document) {
        return query.getResult(document.batchId(), OWNER).documents().getFirst();
    }

    private void runs(UUID documentId, ClassificationRound round, UUID... typeIds) {
        int offset = round == ClassificationRound.INITIAL ? 0 : 100;
        for (int i = 0; i < typeIds.length; i++) {
            classificationRuns.save(
                    documentId,
                    new NewClassificationRun(round, offset + i + 1, "answer", typeIds[i], BigDecimal.ONE, "why", true));
        }
    }

    private void classify(UUID documentId, UUID typeId, boolean tieBroken, DecidedBy decidedBy, String agreement) {
        Map<UUID, Integer> votes = new LinkedHashMap<>();
        votes.put(typeId, 1);
        classificationResults.save(
                documentId,
                new NewClassificationResult(typeId, votes, new BigDecimal(agreement), tieBroken, decidedBy));
        documents.assignType(documentId, typeId);
        documents.updateStatus(documentId, DocumentStatus.CLASSIFIED, null);
    }

    private UUID type(String name) {
        return jdbc.sql("SELECT id FROM document_reader.document_type WHERE name = ?")
                .param(name)
                .query(UUID.class)
                .single();
    }

    private UUID modelType(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.document_type
                            (id, name, normalized_key, description, data_schema, schema_version, origin, created_at)
                        VALUES (?, ?, ?, 'A type the model named', '{"type":"object"}', 1, 'MODEL', now())
                        """)
                .params(id, name, name.toLowerCase(Locale.ROOT).replace(" ", ""))
                .update();
        return id;
    }
}
