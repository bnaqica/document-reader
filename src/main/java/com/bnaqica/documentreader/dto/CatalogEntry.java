package com.bnaqica.documentreader.dto;

import lombok.Builder;

/** One document type as the classification prompts list it. */
@Builder
public record CatalogEntry(String name, String description) {}
