package com.bnaqica.documentreader.dto.response;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

@Builder
public record ExtractionResultRecord(
        UUID documentId, UUID documentTypeId, int schemaVersion, JsonNode data, String summary, Instant createdAt) {}
