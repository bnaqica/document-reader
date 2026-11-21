package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.DecidedBy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ClassificationResultRecord(
        UUID documentId,
        UUID documentTypeId,
        Map<UUID, Integer> votes,
        BigDecimal agreement,
        boolean tieBroken,
        DecidedBy decidedBy,
        Instant decidedAt) {}
