package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.dto.SeparationBoundary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SeparationRunRecord(
        UUID id,
        UUID sourceFileId,
        int runNumber,
        int windowStart,
        int windowEnd,
        boolean succeeded,
        List<SeparationBoundary> boundaries,
        Instant createdAt) {}
