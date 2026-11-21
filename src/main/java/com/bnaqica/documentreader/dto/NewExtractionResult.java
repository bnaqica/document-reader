package com.bnaqica.documentreader.dto;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A document's final extracted data, against version {@code schemaVersion} of its type's schema. */
public record NewExtractionResult(UUID documentTypeId, int schemaVersion, JsonNode data, String summary) {}
