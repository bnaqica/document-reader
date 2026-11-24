package com.bnaqica.documentreader.dto.event;

import java.util.UUID;

/** The {@code CLASSIFY_DOCUMENT} message: which document to classify. */
public record ClassifyDocumentPayload(UUID documentId) {}
