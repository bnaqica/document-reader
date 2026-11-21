package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.StepClock;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.OpenAiCallRecord;
import com.bnaqica.documentreader.dto.response.OpenAiCallLogRecord;
import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@DataJpaTest
@Import({TestcontainersConfiguration.class, StepClockConfig.class, OpenAiCallServiceImpl.class})
class OpenAiCallServiceTests {

    @Autowired
    private OpenAiCallService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StepClock clock;

    private UUID batch;
    private UUID file;
    private UUID document;

    @Autowired
    private EntityManager entityManager;

    /** Writes pending changes and forgets every entity, so the next read comes from Postgres, not Hibernate's cache. */
    private void reread() {
        entityManager.flush();
        entityManager.clear();
    }

    @BeforeEach
    void setUp() {
        clock.reset();
        ProcessingFixtures fixtures = new ProcessingFixtures(jdbc);
        batch = fixtures.batch("user-1");
        file = fixtures.sourceFile(batch, 3);
        document = fixtures.document(batch, file);
    }

    private OpenAiCallRecord.OpenAiCallRecordBuilder call() {
        return OpenAiCallRecord.builder().batchId(batch).sourceFileId(file).model("gpt-4o");
    }

    @Test
    void successfulCallReadsBack() {
        service.record(call().documentId(document)
                .purpose(OpenAiCallPurpose.CLASSIFY)
                .latencyMs(1840)
                .promptTokens(2100)
                .completionTokens(60)
                .httpStatus(200)
                .build());

        reread();

        assertThat(service.findByBatch(batch)).singleElement().satisfies(logged -> {
            assertThat(logged.batchId()).isEqualTo(batch);
            assertThat(logged.sourceFileId()).isEqualTo(file);
            assertThat(logged.documentId()).isEqualTo(document);
            assertThat(logged.purpose()).isEqualTo(OpenAiCallPurpose.CLASSIFY);
            assertThat(logged.model()).isEqualTo("gpt-4o");
            assertThat(logged.latencyMs()).isEqualTo(1840);
            assertThat(logged.promptTokens()).isEqualTo(2100);
            assertThat(logged.completionTokens()).isEqualTo(60);
            assertThat(logged.httpStatus()).isEqualTo(200);
            assertThat(logged.error()).isNull();
            assertThat(logged.createdAt()).isEqualTo(StepClock.START);
        });
    }

    @Test
    void separationCallThatTimedOutHasNoDocumentStatusOrTokens() {
        service.record(call().purpose(OpenAiCallPurpose.SEPARATE)
                .latencyMs(10_000)
                .error("timeout")
                .build());

        reread();

        assertThat(service.findByBatch(batch)).singleElement().satisfies(logged -> {
            assertThat(logged.documentId()).isNull();
            assertThat(logged.httpStatus()).isNull();
            assertThat(logged.promptTokens()).isNull();
            assertThat(logged.completionTokens()).isNull();
            assertThat(logged.error()).isEqualTo("timeout");
        });
    }

    @Test
    void callsListOldestFirst() {
        service.record(call().purpose(OpenAiCallPurpose.SEPARATE).latencyMs(1).build());
        service.record(call().purpose(OpenAiCallPurpose.CLASSIFY).latencyMs(2).build());

        assertThat(service.findByBatch(batch))
                .extracting(OpenAiCallLogRecord::purpose)
                .containsExactly(OpenAiCallPurpose.SEPARATE, OpenAiCallPurpose.CLASSIFY);
    }

    @Test
    void negativeLatencyIsRefused() {
        assertThatThrownBy(() -> service.record(
                        call().purpose(OpenAiCallPurpose.SEPARATE).latencyMs(-5).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void openAiCallIsInIntegrationSchema() {
        assertThat(jdbc.sql("SELECT table_schema FROM information_schema.tables WHERE table_name = 'openai_call'")
                        .query(String.class)
                        .list())
                .containsExactly("document_reader_integration");
    }

    @Test
    void openAiCallHasNoBodyColumns() {
        List<String> columns = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'document_reader_integration' AND table_name = 'openai_call'
                        """).query(String.class).list();

        assertThat(columns).isNotEmpty().doesNotContain("prompt", "response", "body");
    }
}
