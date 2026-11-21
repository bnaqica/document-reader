package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewExtractionResult;
import com.bnaqica.documentreader.dto.response.ExtractionResultRecord;
import com.bnaqica.documentreader.entity.ExtractionResult;
import com.bnaqica.documentreader.repository.ExtractionResultRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExtractionResultServiceImpl implements ExtractionResultService {

    private final ExtractionResultRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    @Transactional
    public ExtractionResultRecord save(UUID documentId, NewExtractionResult result) {
        ExtractionResult saved = repository.saveAndFlush(ExtractionResult.builder()
                .documentId(documentId)
                .documentTypeId(result.documentTypeId())
                .schemaVersion(result.schemaVersion())
                .data(jsonMapper.writeValueAsString(Objects.requireNonNull(result.data(), "data")))
                .summary(result.summary())
                .createdAt(clock.instant())
                .build());
        log.info(
                "Saved extraction result for document {}: type {} schema version {}",
                documentId,
                result.documentTypeId(),
                result.schemaVersion());
        return toRecord(saved);
    }

    @Override
    public Optional<ExtractionResultRecord> find(UUID documentId) {
        return repository.findById(documentId).map(this::toRecord);
    }

    private ExtractionResultRecord toRecord(ExtractionResult result) {
        return ExtractionResultRecord.builder()
                .documentId(result.getDocumentId())
                .documentTypeId(result.getDocumentTypeId())
                .schemaVersion(result.getSchemaVersion())
                .data(jsonMapper.readTree(result.getData()))
                .summary(result.getSummary())
                .createdAt(result.getCreatedAt())
                .build();
    }
}
