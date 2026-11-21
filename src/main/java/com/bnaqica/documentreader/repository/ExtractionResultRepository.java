package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.ExtractionResult;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractionResultRepository extends JpaRepository<ExtractionResult, UUID> {}
