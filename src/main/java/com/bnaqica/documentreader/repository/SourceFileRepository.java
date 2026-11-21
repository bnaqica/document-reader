package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.SourceFile;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {

    List<SourceFile> findByBatchIdOrderByFileIndex(UUID batchId);
}
