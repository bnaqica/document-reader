package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

@Builder
public record InboxMessageRecord(
        UUID id,
        MessageType messageType,
        UUID aggregateId,
        JsonNode payload,
        InboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        String lockedBy,
        Instant lockedUntil,
        String lastError,
        Instant createdAt,
        Instant completedAt) {}
