package com.bnaqica.documentreader.dto;

import com.bnaqica.documentreader.entity.ClassificationRound;
import java.math.BigDecimal;
import java.util.UUID;

/** One classification answer to save. {@code documentTypeId} is null when the run failed or didn't resolve. */
public record NewClassificationRun(
        ClassificationRound round,
        int runNumber,
        String rawAnswer,
        UUID documentTypeId,
        BigDecimal confidence,
        String reasoning,
        boolean succeeded) {}
