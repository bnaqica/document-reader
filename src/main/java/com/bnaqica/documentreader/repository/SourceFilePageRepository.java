package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.SourceFilePage;
import com.bnaqica.documentreader.entity.SourceFilePageId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceFilePageRepository extends JpaRepository<SourceFilePage, SourceFilePageId> {

    @Query("SELECT p FROM SourceFilePage p WHERE p.id.sourceFileId = :sourceFileId ORDER BY p.id.pageNumber")
    List<SourceFilePage> findBySourceFile(@Param("sourceFileId") UUID sourceFileId);

    /** Flushes pending changes first and clears the persistence context after, so no stale page survives. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM SourceFilePage p WHERE p.id.sourceFileId = :sourceFileId")
    void deleteBySourceFile(@Param("sourceFileId") UUID sourceFileId);
}
