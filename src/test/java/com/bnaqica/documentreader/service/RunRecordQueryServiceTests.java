package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.NewClassificationResult;
import com.bnaqica.documentreader.dto.NewClassificationRun;
import com.bnaqica.documentreader.dto.NewExtractionResult;
import com.bnaqica.documentreader.dto.NewExtractionRun;
import com.bnaqica.documentreader.dto.NewSeparationRun;
import com.bnaqica.documentreader.dto.OpenAiCallRecord;
import com.bnaqica.documentreader.dto.SeparationBoundary;
import com.bnaqica.documentreader.dto.response.ExtractionRunRecord;
import com.bnaqica.documentreader.entity.ClassificationRound;
import com.bnaqica.documentreader.entity.DecidedBy;
import com.bnaqica.documentreader.entity.ExtractionStage;
import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    StepClockConfig.class,
    RunRecordQueryServiceImpl.class,
    RecordQueryServiceImpl.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class,
    SourceFileServiceImpl.class,
    SourceFilePageServiceImpl.class,
    DocumentServiceImpl.class,
    DocumentTypeServiceImpl.class,
    SeparationRunServiceImpl.class,
    ClassificationRunServiceImpl.class,
    ClassificationResultServiceImpl.class,
    ExtractionRunServiceImpl.class,
    ExtractionResultServiceImpl.class,
    WebhookDeliveryAttemptServiceImpl.class,
    OpenAiCallServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class RunRecordQueryServiceTests {

    private static final Caller OWNER = new Caller("user-1", false);
    private static final Caller OTHER = new Caller("user-2", false);
    private static final Caller ADMIN = new Caller("admin-1", true);

    @Autowired
    private RunRecordQueryService runs;

    @Autowired
    private SeparationRunService separationRuns;

    @Autowired
    private ClassificationRunService classificationRuns;

    @Autowired
    private ClassificationResultService classificationResults;

    @Autowired
    private ExtractionRunService extractionRuns;

    @Autowired
    private ExtractionResultService extractionResults;

    @Autowired
    private WebhookDeliveryAttemptService webhookAttempts;

    @Autowired
    private OpenAiCallService openAiCalls;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    private ProcessingFixtures fixtures;
    private UUID batch;
    private UUID file;
    private UUID document;

    @BeforeEach
    void batchOfUser1WithOneOfEverything() {
        fixtures = new ProcessingFixtures(jdbc);
        batch = fixtures.batch("user-1");
        file = fixtures.sourceFile(batch, 2);
        document = fixtures.document(batch, file);
        UUID payStubs = fixtures.type("Pay stubs");

        separationRuns.save(file, new NewSeparationRun(1, 1, 2, true, List.of(new SeparationBoundary(1, "Paystub"))));
        classificationRuns.save(
                document,
                new NewClassificationRun(
                        ClassificationRound.INITIAL, 1, "Pay stub", payStubs, BigDecimal.ONE, "pay period", true));
        classificationResults.save(
                document,
                new NewClassificationResult(payStubs, Map.of(payStubs, 1), BigDecimal.ONE, false, DecidedBy.VOTE));
        extractionRuns.save(
                document, new NewExtractionRun(ExtractionStage.INITIAL, 1, jsonMapper.readTree("{}"), "first"));
        extractionRuns.save(
                document, new NewExtractionRun(ExtractionStage.FINAL, 1, jsonMapper.readTree("{}"), "final"));
        extractionResults.save(document, new NewExtractionResult(payStubs, 1, jsonMapper.readTree("{}"), "final"));
        webhookAttempts.record(batch, 1, 200, null);
        openAiCalls.record(OpenAiCallRecord.builder()
                .batchId(batch)
                .sourceFileId(file)
                .purpose(OpenAiCallPurpose.SEPARATE)
                .model("gpt-4o")
                .latencyMs(100)
                .build());
    }

    @Test
    void ownerReadsEveryRunAndResult() {
        assertThat(runs.separationRuns(file, OWNER)).hasSize(1);
        assertThat(runs.classificationRuns(document, OWNER)).hasSize(1);
        assertThat(runs.classificationResult(document, OWNER).documentId()).isEqualTo(document);
        assertThat(runs.extractionRuns(document, null, OWNER)).hasSize(2);
        assertThat(runs.extractionRuns(document, ExtractionStage.FINAL, OWNER))
                .extracting(ExtractionRunRecord::summary)
                .containsExactly("final");
        assertThat(runs.extractionResult(document, OWNER).documentId()).isEqualTo(document);
        assertThat(runs.webhookAttempts(batch, OWNER)).hasSize(1);
        assertThat(runs.openAiCalls(batch, OWNER)).hasSize(1);
    }

    @Test
    void anotherCallerFindsNothing() {
        List<ThrowingCallable> reads = List.of(
                () -> runs.separationRuns(file, OTHER),
                () -> runs.classificationRuns(document, OTHER),
                () -> runs.classificationResult(document, OTHER),
                () -> runs.extractionRuns(document, null, OTHER),
                () -> runs.extractionResult(document, OTHER),
                () -> runs.webhookAttempts(batch, OTHER),
                () -> runs.openAiCalls(batch, OTHER));

        reads.forEach(read -> assertThatThrownBy(read).isInstanceOf(ResourceNotFoundException.class));
    }

    @Test
    void adminReadsAnyBatch() {
        assertThat(runs.separationRuns(file, ADMIN)).hasSize(1);
        assertThat(runs.classificationResult(document, ADMIN).documentId()).isEqualTo(document);
        assertThat(runs.openAiCalls(batch, ADMIN)).hasSize(1);
    }

    @Test
    void missingResultIsNotFound() {
        UUID unprocessed = fixtures.document(); // in another batch of user-1

        assertThatThrownBy(() -> runs.classificationResult(unprocessed, OWNER))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> runs.extractionResult(unprocessed, OWNER))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
