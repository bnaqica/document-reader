package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.ClassificationRound;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ClassificationRunRecord(
        UUID id,
        UUID documentId,
        ClassificationRound round,
        int runNumber,
        String rawAnswer,
        UUID documentTypeId,
        BigDecimal confidence,
        String reasoning,
        boolean succeeded,
        Instant createdAt) {}
