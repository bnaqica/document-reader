package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.BatchStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/** The batch result envelope (§7.3): the batch, a short status per uploaded file, and one payload per document. */
@Builder
public record BatchResultResponse(
        UUID batchId,
        BatchStatus status,
        String loanPurpose,
        Instant receivedAt,
        Instant completedAt,
        String error,
        List<FileResultResponse> files,
        List<DocumentResultResponse> documents) {}
