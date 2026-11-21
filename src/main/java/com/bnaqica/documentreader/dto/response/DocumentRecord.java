package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.DocumentStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record DocumentRecord(
        UUID id,
        UUID batchId,
        UUID sourceFileId,
        int sequence,
        int startPage,
        int endPage,
        DocumentStatus status,
        UUID documentTypeId,
        boolean truncated,
        String failureReason,
        Instant completedAt) {}
