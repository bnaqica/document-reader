package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.ClassificationRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassificationRunRepository extends JpaRepository<ClassificationRun, UUID> {

    /** {@code round} is stored by name, and {@code INITIAL} sorts before {@code TIEBREAK}. */
    List<ClassificationRun> findByDocumentIdOrderByRoundAscRunNumberAsc(UUID documentId);
}
