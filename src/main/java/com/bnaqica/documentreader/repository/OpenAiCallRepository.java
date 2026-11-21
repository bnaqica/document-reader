package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.OpenAiCall;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpenAiCallRepository extends JpaRepository<OpenAiCall, UUID> {

    List<OpenAiCall> findByBatchIdOrderByCreatedAtAscIdAsc(UUID batchId);
}
