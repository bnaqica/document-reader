package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.Document;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /** Every document of the batch's first file in sequence order, then the next file's, and so on. */
    @Query("""
            SELECT d FROM Document d, SourceFile f
            WHERE f.id = d.sourceFileId AND d.batchId = :batchId
            ORDER BY f.fileIndex, d.sequence
            """)
    List<Document> findByBatchInFileOrder(@Param("batchId") UUID batchId);

    List<Document> findBySourceFileIdOrderBySequence(UUID sourceFileId);
}
