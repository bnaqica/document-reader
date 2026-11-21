package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.ExtractionStage;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

@Builder
public record ExtractionRunRecord(
        UUID id,
        UUID documentId,
        ExtractionStage stage,
        int passNumber,
        JsonNode data,
        String summary,
        Instant createdAt) {}
