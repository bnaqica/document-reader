package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewClassificationRun;
import com.bnaqica.documentreader.dto.response.ClassificationRunRecord;
import com.bnaqica.documentreader.entity.ClassificationRun;
import com.bnaqica.documentreader.repository.ClassificationRunRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClassificationRunServiceImpl implements ClassificationRunService {

    private final ClassificationRunRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public ClassificationRunRecord save(UUID documentId, NewClassificationRun run) {
        ClassificationRun saved = repository.saveAndFlush(ClassificationRun.builder()
                .id(UUID.randomUUID())
                .documentId(documentId)
                .round(run.round())
                .runNumber(run.runNumber())
                .rawAnswer(run.rawAnswer())
                .documentTypeId(run.documentTypeId())
                .confidence(run.confidence())
                .reasoning(run.reasoning())
                .succeeded(run.succeeded())
                .createdAt(clock.instant())
                .build());
        log.info(
                "Saved {} classification run {} for document {} (succeeded: {})",
                run.round(),
                run.runNumber(),
                documentId,
                run.succeeded());
        return toRecord(saved);
    }

    @Override
    public List<ClassificationRunRecord> findByDocument(UUID documentId) {
        return repository.findByDocumentIdOrderByRoundAscRunNumberAsc(documentId).stream()
                .map(ClassificationRunServiceImpl::toRecord)
                .toList();
    }

    private static ClassificationRunRecord toRecord(ClassificationRun run) {
        return ClassificationRunRecord.builder()
                .id(run.getId())
                .documentId(run.getDocumentId())
                .round(run.getRound())
                .runNumber(run.getRunNumber())
                .rawAnswer(run.getRawAnswer())
                .documentTypeId(run.getDocumentTypeId())
                .confidence(run.getConfidence())
                .reasoning(run.getReasoning())
                .succeeded(run.isSucceeded())
                .createdAt(run.getCreatedAt())
                .build();
    }
}
