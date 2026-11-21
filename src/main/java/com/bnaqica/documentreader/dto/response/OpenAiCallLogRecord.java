package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/** A logged OpenAI call. Named apart from the {@code OpenAiCallRecord} input. */
@Builder
public record OpenAiCallLogRecord(
        UUID id,
        UUID batchId,
        UUID sourceFileId,
        UUID documentId,
        OpenAiCallPurpose purpose,
        String model,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer httpStatus,
        String error,
        Instant createdAt) {}
