package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewExtractionRun;
import com.bnaqica.documentreader.dto.response.ExtractionRunRecord;
import com.bnaqica.documentreader.entity.ExtractionRun;
import com.bnaqica.documentreader.entity.ExtractionStage;
import com.bnaqica.documentreader.repository.ExtractionRunRepository;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
public class ExtractionRunServiceImpl implements ExtractionRunService {

    /** Stage names don't sort in stage order, so sort by the enum's declaration order. */
    private static final Comparator<ExtractionRun> STAGE_THEN_PASS =
            Comparator.comparing(ExtractionRun::getStage).thenComparingInt(ExtractionRun::getPassNumber);

    private final ExtractionRunRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    @Transactional
    public ExtractionRunRecord save(UUID documentId, NewExtractionRun run) {
        ExtractionRun saved = repository.saveAndFlush(ExtractionRun.builder()
                .id(UUID.randomUUID())
                .documentId(documentId)
                .stage(run.stage())
                .passNumber(run.passNumber())
                .data(jsonMapper.writeValueAsString(Objects.requireNonNull(run.data(), "data")))
                .summary(run.summary())
                .createdAt(clock.instant())
                .build());
        log.info("Saved {} extraction pass {} for document {}", run.stage(), run.passNumber(), documentId);
        return toRecord(saved);
    }

    @Override
    public List<ExtractionRunRecord> findByDocument(UUID documentId) {
        return repository.findByDocumentId(documentId).stream()
                .sorted(STAGE_THEN_PASS)
                .map(this::toRecord)
                .toList();
    }

    @Override
    public List<ExtractionRunRecord> findByDocumentAndStage(UUID documentId, ExtractionStage stage) {
        return repository.findByDocumentIdAndStageOrderByPassNumberAsc(documentId, stage).stream()
                .map(this::toRecord)
                .toList();
    }

    private ExtractionRunRecord toRecord(ExtractionRun run) {
        return ExtractionRunRecord.builder()
                .id(run.getId())
                .documentId(run.getDocumentId())
                .stage(run.getStage())
                .passNumber(run.getPassNumber())
                .data(jsonMapper.readTree(run.getData()))
                .summary(run.getSummary())
                .createdAt(run.getCreatedAt())
                .build();
    }
}
