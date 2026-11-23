package com.bnaqica.documentreader.dto;

import java.util.UUID;
import lombok.Builder;

/**
 * What a vision call is for: the ids it is logged under, and the loan purpose every prompt includes. {@code documentId}
 * is null for separation, which runs before documents exist.
 */
@Builder
public record CallContext(UUID batchId, UUID sourceFileId, UUID documentId, String loanPurposeDescription) {}
