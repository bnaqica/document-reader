package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.ClassificationResult;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassificationResultRepository extends JpaRepository<ClassificationResult, UUID> {}
