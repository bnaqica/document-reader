package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.DocumentTypeOrigin;
import java.util.UUID;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

@Builder
public record DocumentTypeResponse(
        UUID id,
        String name,
        String normalizedKey,
        String description,
        JsonNode dataSchema,
        int schemaVersion,
        DocumentTypeOrigin origin) {}
