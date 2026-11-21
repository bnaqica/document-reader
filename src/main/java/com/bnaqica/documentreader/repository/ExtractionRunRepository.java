package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.ExtractionRun;
import com.bnaqica.documentreader.entity.ExtractionStage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, UUID> {

    List<ExtractionRun> findByDocumentId(UUID documentId);

    List<ExtractionRun> findByDocumentIdAndStageOrderByPassNumberAsc(UUID documentId, ExtractionStage stage);
}
