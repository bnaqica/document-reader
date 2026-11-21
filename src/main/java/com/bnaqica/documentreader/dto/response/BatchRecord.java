package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.WebhookStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record BatchRecord(
        UUID id,
        String loanPurposeCode,
        boolean async,
        String webhookUrl,
        BatchStatus status,
        WebhookStatus webhookStatus,
        String failureReason,
        int fileCount,
        String requestedBy,
        Instant receivedAt,
        Instant completedAt) {}
