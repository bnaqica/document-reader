package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.SeparationRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeparationRunRepository extends JpaRepository<SeparationRun, UUID> {

    List<SeparationRun> findBySourceFileIdOrderByWindowStartAscRunNumberAsc(UUID sourceFileId);
}
