package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewClassificationResult;
import com.bnaqica.documentreader.dto.response.ClassificationResultRecord;
import com.bnaqica.documentreader.entity.ClassificationResult;
import com.bnaqica.documentreader.repository.ClassificationResultRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClassificationResultServiceImpl implements ClassificationResultService {

    private static final TypeReference<LinkedHashMap<UUID, Integer>> VOTES = new TypeReference<>() {};

    private final ClassificationResultRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    @Transactional
    public ClassificationResultRecord save(UUID documentId, NewClassificationResult result) {
        ClassificationResult saved = repository.saveAndFlush(ClassificationResult.builder()
                .documentId(documentId)
                .documentTypeId(result.documentTypeId())
                .votes(jsonMapper.writeValueAsString(Objects.requireNonNull(result.votes(), "votes")))
                .agreement(result.agreement())
                .tieBroken(result.tieBroken())
                .decidedBy(result.decidedBy())
                .decidedAt(clock.instant())
                .build());
        log.info(
                "Saved classification result for document {}: type {} decided by {}",
                documentId,
                result.documentTypeId(),
                result.decidedBy());
        return toRecord(saved);
    }

    @Override
    public Optional<ClassificationResultRecord> find(UUID documentId) {
        return repository.findById(documentId).map(this::toRecord);
    }

    private ClassificationResultRecord toRecord(ClassificationResult result) {
        Map<UUID, Integer> votes = jsonMapper.readValue(result.getVotes(), VOTES);
        return ClassificationResultRecord.builder()
                .documentId(result.getDocumentId())
                .documentTypeId(result.getDocumentTypeId())
                .votes(votes)
                .agreement(result.getAgreement())
                .tieBroken(result.isTieBroken())
                .decidedBy(result.getDecidedBy())
                .decidedAt(result.getDecidedAt())
                .build();
    }
}
