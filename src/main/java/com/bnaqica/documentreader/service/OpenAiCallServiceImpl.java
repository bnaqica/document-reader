package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.OpenAiCallRecord;
import com.bnaqica.documentreader.dto.response.OpenAiCallLogRecord;
import com.bnaqica.documentreader.entity.OpenAiCall;
import com.bnaqica.documentreader.repository.OpenAiCallRepository;
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
public class OpenAiCallServiceImpl implements OpenAiCallService {

    private final OpenAiCallRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public OpenAiCallLogRecord record(OpenAiCallRecord call) {
        OpenAiCall saved = repository.saveAndFlush(OpenAiCall.builder()
                .id(UUID.randomUUID())
                .batchId(call.batchId())
                .sourceFileId(call.sourceFileId())
                .documentId(call.documentId())
                .purpose(call.purpose())
                .model(call.model())
                .latencyMs(call.latencyMs())
                .promptTokens(call.promptTokens())
                .completionTokens(call.completionTokens())
                .httpStatus(call.httpStatus())
                .error(call.error())
                .createdAt(clock.instant())
                .build());
        log.info(
                "Logged {} OpenAI call for batch {} ({} ms, status {})",
                call.purpose(),
                call.batchId(),
                call.latencyMs(),
                call.httpStatus());
        return toRecord(saved);
    }

    @Override
    public List<OpenAiCallLogRecord> findByBatch(UUID batchId) {
        return repository.findByBatchIdOrderByCreatedAtAscIdAsc(batchId).stream()
                .map(OpenAiCallServiceImpl::toRecord)
                .toList();
    }

    private static OpenAiCallLogRecord toRecord(OpenAiCall call) {
        return OpenAiCallLogRecord.builder()
                .id(call.getId())
                .batchId(call.getBatchId())
                .sourceFileId(call.getSourceFileId())
                .documentId(call.getDocumentId())
                .purpose(call.getPurpose())
                .model(call.getModel())
                .latencyMs(call.getLatencyMs())
                .promptTokens(call.getPromptTokens())
                .completionTokens(call.getCompletionTokens())
                .httpStatus(call.getHttpStatus())
                .error(call.getError())
                .createdAt(call.getCreatedAt())
                .build();
    }
}
