package com.bnaqica.documentreader.dto;

import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import java.util.UUID;
import lombok.Builder;

/**
 * One OpenAI call to log. {@code documentId} is null for separation; tokens and {@code httpStatus} are null when
 * unknown or when no response came. Never carries the prompt or the response.
 */
@Builder
public record OpenAiCallRecord(
        UUID batchId,
        UUID sourceFileId,
        UUID documentId,
        OpenAiCallPurpose purpose,
        String model,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer httpStatus,
        String error) {}
