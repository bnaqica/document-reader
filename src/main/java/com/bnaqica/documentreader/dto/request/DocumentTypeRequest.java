package com.bnaqica.documentreader.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Creates a document type, or replaces one's name, description and data schema. */
public record DocumentTypeRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank String description,
        @NotNull JsonNode dataSchema) {}
