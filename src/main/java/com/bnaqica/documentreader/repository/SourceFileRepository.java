package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.SourceFile;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {

    /** {@code SELECT … FOR UPDATE}: a second locker waits until this transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM SourceFile f WHERE f.id = :id")
    Optional<SourceFile> findByIdForUpdate(@Param("id") UUID id);

    List<SourceFile> findByBatchIdOrderByFileIndex(UUID batchId);
}
