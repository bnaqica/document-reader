package com.bnaqica.documentreader.dto.event;

import java.util.UUID;

/** The {@code EXTRACT_DOCUMENT} message: which classified document to extract. */
public record ExtractDocumentPayload(UUID documentId) {}
