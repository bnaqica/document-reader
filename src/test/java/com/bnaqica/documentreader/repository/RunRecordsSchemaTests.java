package com.bnaqica.documentreader.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What the database itself refuses for runs and results, whatever the application does. Plain SQL. */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class RunRecordsSchemaTests {

    @Autowired
    private JdbcClient jdbc;

    private UUID batch;
    private UUID file;
    private UUID document;
    private UUID type;

    @BeforeEach
    void batchWithOneFileAndDocument() {
        batch = UUID.randomUUID();
        file = UUID.randomUUID();
        document = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.batch
                            (id, loan_purpose_code, async, status, webhook_status, file_count, requested_by, received_at)
                        VALUES (?, 'CAR_REFINANCE', false, 'RECEIVED', 'NOT_APPLICABLE', 1, 'user-1', now())
                        """).param(batch).update();
        jdbc.sql("""
                        INSERT INTO document_reader.source_file
                            (id, batch_id, file_index, original_filename, content_type, size_bytes, sha256, page_count, status)
                        VALUES (?, ?, 0, 'scan.pdf', 'application/pdf', 100, repeat('a', 64), 6, 'SEPARATING')
                        """).params(file, batch).update();
        jdbc.sql("""
                        INSERT INTO document_reader.document
                            (id, batch_id, source_file_id, sequence, start_page, end_page, status)
                        VALUES (?, ?, ?, 1, 1, 6, 'CLASSIFYING')
                        """).params(document, batch, file).update();
        type = jdbc.sql("SELECT id FROM document_reader.document_type ORDER BY name LIMIT 1")
                .query(UUID.class)
                .single();
    }

    private void separationRun(int runNumber, int windowStart, int windowEnd, boolean succeeded, String boundaries) {
        jdbc.sql("""
                        INSERT INTO document_reader.separation_run
                            (id, source_file_id, run_number, window_start, window_end, succeeded, boundaries, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?::json, now())
                        """)
                .params(UUID.randomUUID(), file, runNumber, windowStart, windowEnd, succeeded, boundaries)
                .update();
    }

    private void classificationRun(String round, int runNumber, Double confidence) {
        jdbc.sql("""
                        INSERT INTO document_reader.classification_run
                            (id, document_id, round, run_number, raw_answer, document_type_id, confidence,
                             reasoning, succeeded, created_at)
                        VALUES (?, ?, ?, ?, 'Pay stub', ?, ?, 'header says pay stub', true, now())
                        """)
                .params(UUID.randomUUID(), document, round, runNumber, type, confidence)
                .update();
    }

    private void classificationResult(double agreement) {
        jdbc.sql("""
                        INSERT INTO document_reader.classification_result
                            (document_id, document_type_id, votes, agreement, tie_broken, decided_by, decided_at)
                        VALUES (?, ?, '{}'::json, ?, false, 'VOTE', now())
                        """).params(document, type, agreement).update();
    }

    private void extractionRun(String stage, int passNumber) {
        jdbc.sql("""
                        INSERT INTO document_reader.extraction_run
                            (id, document_id, stage, pass_number, data, summary, created_at)
                        VALUES (?, ?, ?, ?, '{}'::json, 'summary', now())
                        """).params(UUID.randomUUID(), document, stage, passNumber).update();
    }

    private void webhookAttempt(int attempt) {
        jdbc.sql("""
                        INSERT INTO document_reader.webhook_delivery_attempt
                            (id, batch_id, attempt, http_status, error, attempted_at)
                        VALUES (?, ?, ?, 500, 'server error', now())
                        """).params(UUID.randomUUID(), batch, attempt).update();
    }

    @Test
    void separationRunNumberIsUsedOncePerWindow() {
        separationRun(1, 1, 6, true, "[]");

        assertThatThrownBy(() -> separationRun(1, 1, 6, true, "[]"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void separationWindowCannotEndBeforeItStarts() {
        assertThatThrownBy(() -> separationRun(1, 4, 3, true, "[]"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void failedSeparationRunHasNoBoundaries() {
        assertThatThrownBy(() -> separationRun(1, 1, 6, false, "[]"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void classificationRunNumberIsUsedOncePerRound() {
        classificationRun("INITIAL", 3, 0.9);

        assertThatThrownBy(() -> classificationRun("INITIAL", 3, 0.9))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameRunNumberMayBeUsedInEachRound() {
        classificationRun("INITIAL", 1, 0.9);
        classificationRun("TIEBREAK", 1, 0.8);

        assertThat(jdbc.sql("SELECT count(*) FROM document_reader.classification_run WHERE document_id = ?")
                        .param(document)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void confidenceIsAtMostOne() {
        assertThatThrownBy(() -> classificationRun("INITIAL", 1, 1.2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void documentHasAtMostOneClassificationResult() {
        classificationResult(0.8);

        assertThatThrownBy(() -> classificationResult(0.8)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void agreementIsAtLeastZero() {
        assertThatThrownBy(() -> classificationResult(-0.1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void extractionPassNumberIsUsedOncePerStage() {
        extractionRun("INITIAL", 1);

        assertThatThrownBy(() -> extractionRun("INITIAL", 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void passNumbersStartAtOne() {
        assertThatThrownBy(() -> extractionRun("INITIAL", 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void webhookAttemptNumberIsUsedOncePerBatch() {
        webhookAttempt(1);

        assertThatThrownBy(() -> webhookAttempt(1)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
