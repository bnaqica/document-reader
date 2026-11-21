package com.bnaqica.documentreader.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What the database itself refuses, whatever the application does. Plain SQL, so no entity mapping is involved. */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ProcessingRecordsSchemaTests {

    @Autowired
    private JdbcClient jdbc;

    private UUID batch() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.batch
                            (id, loan_purpose_code, async, status, webhook_status, file_count, requested_by, received_at)
                        VALUES (?, 'CAR_REFINANCE', false, 'RECEIVED', 'NOT_APPLICABLE', 1, 'user-1', now())
                        """).param(id).update();
        return id;
    }

    private UUID sourceFile(UUID batchId, int index) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.source_file
                            (id, batch_id, file_index, original_filename, content_type, size_bytes, sha256, page_count, status)
                        VALUES (?, ?, ?, 'scan.pdf', 'application/pdf', 100, repeat('a', 64), 6, 'RECEIVED')
                        """).params(id, batchId, index).update();
        return id;
    }

    @Test
    void twoFilesCannotShareAnIndexInOneBatch() {
        UUID batch = batch();
        sourceFile(batch, 0);

        assertThatThrownBy(() -> sourceFile(batch, 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoPagesCannotShareANumberInOneFile() {
        UUID file = sourceFile(batch(), 0);
        String insertPage =
                "INSERT INTO document_reader.source_file_page (source_file_id, page_number, s3_key, width, height) "
                        + "VALUES (?, 1, 'files/x/pages/0001.png', 1700, 2200)";
        jdbc.sql(insertPage).param(file).update();

        assertThatThrownBy(() -> jdbc.sql(insertPage).param(file).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void documentCannotEndBeforeItStarts() {
        UUID batch = batch();
        UUID file = sourceFile(batch, 0);

        assertThatThrownBy(() ->
                        jdbc.sql("""
                                INSERT INTO document_reader.document
                                    (id, batch_id, source_file_id, sequence, start_page, end_page, status)
                                VALUES (?, ?, ?, 1, 3, 2, 'PENDING')
                                """).params(UUID.randomUUID(), batch, file).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void batchCannotReferToAnUnknownLoanPurpose() {
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO document_reader.batch
                                    (id, loan_purpose_code, async, status, webhook_status, file_count, requested_by, received_at)
                                VALUES (?, 'SPACESHIP_PURCHASE', false, 'RECEIVED', 'NOT_APPLICABLE', 1, 'user-1', now())
                                """).param(UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void batchStatusMustBeOneOfTheFive() {
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO document_reader.batch
                                    (id, loan_purpose_code, async, status, webhook_status, file_count, requested_by, received_at)
                                VALUES (?, 'CAR_REFINANCE', false, 'LOST', 'NOT_APPLICABLE', 1, 'user-1', now())
                                """).param(UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void documentMustBelongToItsFilesBatch() {
        UUID batchOfFile = batch();
        UUID otherBatch = batch();
        UUID file = sourceFile(batchOfFile, 0);

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO document_reader.document
                                    (id, batch_id, source_file_id, sequence, start_page, end_page, status)
                                VALUES (?, ?, ?, 1, 1, 2, 'PENDING')
                                """)
                        .params(UUID.randomUUID(), otherBatch, file)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
