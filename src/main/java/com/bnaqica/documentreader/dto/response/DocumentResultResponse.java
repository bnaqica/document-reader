package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.DecidedBy;
import com.bnaqica.documentreader.entity.DocumentStatus;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

/**
 * One document's payload in the result envelope. {@code documentType} and {@code classification} are null until the
 * document is classified, and {@code summary} and {@code data} until it is extracted.
 */
@Builder
public record DocumentResultResponse(
        UUID id,
        UUID sourceFileId,
        Pages pages,
        DocumentStatus status,
        DocumentTypeSummary documentType,
        Classification classification,
        String summary,
        JsonNode data,
        boolean truncated,
        String error) {

    public record Pages(int start, int end) {}

    /** {@code newType} is true for a type the model created during classification. */
    public record DocumentTypeSummary(String name, boolean newType) {}

    /** The first-round vote; {@code tieBreak} is null unless that vote was tied. */
    @Builder
    public record Classification(int runs, Map<String, Integer> votes, BigDecimal agreement, TieBreak tieBreak) {}

    public record TieBreak(int runs, Map<String, Integer> votes, DecidedBy decidedBy) {}
}
