package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewSeparationRun;
import com.bnaqica.documentreader.dto.SeparationBoundary;
import com.bnaqica.documentreader.dto.response.SeparationRunRecord;
import com.bnaqica.documentreader.entity.SeparationRun;
import com.bnaqica.documentreader.repository.SeparationRunRepository;
import java.time.Clock;
import java.util.List;
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
public class SeparationRunServiceImpl implements SeparationRunService {

    private static final TypeReference<List<SeparationBoundary>> BOUNDARIES = new TypeReference<>() {};

    private final SeparationRunRepository repository;
    private final SourceFileService sourceFiles;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    @Transactional
    public SeparationRunRecord save(UUID sourceFileId, NewSeparationRun run) {
        int pageCount = sourceFiles.get(sourceFileId).pageCount();
        if (run.windowEnd() > pageCount) {
            throw new IllegalArgumentException(
                    "Window ends at page " + run.windowEnd() + " but the file has " + pageCount + " pages");
        }
        SeparationRun saved = repository.saveAndFlush(SeparationRun.builder()
                .id(UUID.randomUUID())
                .sourceFileId(sourceFileId)
                .runNumber(run.runNumber())
                .windowStart(run.windowStart())
                .windowEnd(run.windowEnd())
                .succeeded(run.succeeded())
                .boundaries(run.boundaries() == null ? null : jsonMapper.writeValueAsString(run.boundaries()))
                .createdAt(clock.instant())
                .build());
        log.info(
                "Saved separation run {} over pages {}-{} for source file {}",
                run.runNumber(),
                run.windowStart(),
                run.windowEnd(),
                sourceFileId);
        return toRecord(saved);
    }

    @Override
    public List<SeparationRunRecord> findBySourceFile(UUID sourceFileId) {
        return repository.findBySourceFileIdOrderByWindowStartAscRunNumberAsc(sourceFileId).stream()
                .map(this::toRecord)
                .toList();
    }

    private SeparationRunRecord toRecord(SeparationRun run) {
        return SeparationRunRecord.builder()
                .id(run.getId())
                .sourceFileId(run.getSourceFileId())
                .runNumber(run.getRunNumber())
                .windowStart(run.getWindowStart())
                .windowEnd(run.getWindowEnd())
                .succeeded(run.isSucceeded())
                .boundaries(run.getBoundaries() == null ? null : jsonMapper.readValue(run.getBoundaries(), BOUNDARIES))
                .createdAt(run.getCreatedAt())
                .build();
    }
}
