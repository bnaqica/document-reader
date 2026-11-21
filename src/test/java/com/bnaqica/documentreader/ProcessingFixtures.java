package com.bnaqica.documentreader;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Plain-SQL rows a run or result needs as its parent, for tests that don't exercise the WI-03 services. */
public final class ProcessingFixtures {

    private final JdbcClient jdbc;

    public ProcessingFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID batch(String requestedBy) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.batch
                            (id, loan_purpose_code, async, status, webhook_status, file_count, requested_by, received_at)
                        VALUES (?, 'CAR_REFINANCE', true, 'PROCESSING', 'PENDING', 1, ?, now())
                        """).params(id, requestedBy).update();
        return id;
    }

    public UUID sourceFile(UUID batchId, int pageCount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.source_file
                            (id, batch_id, file_index, original_filename, content_type, size_bytes, sha256, page_count, status)
                        VALUES (?, ?, 0, 'scan.pdf', 'application/pdf', 100, repeat('a', 64), ?, 'SEPARATING')
                        """).params(id, batchId, pageCount).update();
        return id;
    }

    public UUID document(UUID batchId, UUID sourceFileId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.document
                            (id, batch_id, source_file_id, sequence, start_page, end_page, status)
                        VALUES (?, ?, ?, 1, 1, 1, 'CLASSIFYING')
                        """).params(id, batchId, sourceFileId).update();
        return id;
    }

    /** A document in a fresh batch of {@code user-1}. */
    public UUID document() {
        UUID batch = batch("user-1");
        return document(batch, sourceFile(batch, 1));
    }

    /** The id of a seeded catalog type, by name. */
    public UUID type(String name) {
        return jdbc.sql("SELECT id FROM document_reader.document_type WHERE name = ?")
                .param(name)
                .query(UUID.class)
                .single();
    }

    /** Deletes a batch and every row under it, for tests that commit outside the test transaction. */
    public void deleteBatch(UUID batchId) {
        String documents = "(SELECT id FROM document_reader.document WHERE batch_id = :batch)";
        for (String table :
                new String[] {"classification_run", "classification_result", "extraction_run", "extraction_result"}) {
            jdbc.sql("DELETE FROM document_reader." + table + " WHERE document_id IN " + documents)
                    .param("batch", batchId)
                    .update();
        }
        jdbc.sql("DELETE FROM document_reader.separation_run WHERE source_file_id IN "
                        + "(SELECT id FROM document_reader.source_file WHERE batch_id = :batch)")
                .param("batch", batchId)
                .update();
        for (String table : new String[] {"webhook_delivery_attempt", "document", "source_file"}) {
            jdbc.sql("DELETE FROM document_reader." + table + " WHERE batch_id = :batch")
                    .param("batch", batchId)
                    .update();
        }
        jdbc.sql("DELETE FROM document_reader.batch WHERE id = :batch")
                .param("batch", batchId)
                .update();
    }

    /** The batch a document belongs to. */
    public UUID batchOf(UUID documentId) {
        return jdbc.sql("SELECT batch_id FROM document_reader.document WHERE id = ?")
                .param(documentId)
                .query(UUID.class)
                .single();
    }
}
