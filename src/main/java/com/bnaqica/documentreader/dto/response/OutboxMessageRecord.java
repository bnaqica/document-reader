package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.MessageType;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

@Builder
public record OutboxMessageRecord(
        UUID id,
        MessageType messageType,
        UUID aggregateId,
        JsonNode payload,
        Instant createdAt,
        Instant dispatchedAt) {}
