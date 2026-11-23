package com.bnaqica.documentreader.dto;

import tools.jackson.databind.JsonNode;

/** One extraction pass, or the final one: {@code data} follows the document type's schema. */
public record ExtractionAnswer(JsonNode data, String summary) {}
