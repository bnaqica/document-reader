package com.bnaqica.documentreader.dto;

import com.bnaqica.documentreader.entity.DecidedBy;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** The decided type of a document. {@code votes} maps type id to vote count and is stored in its iteration order. */
public record NewClassificationResult(
        UUID documentTypeId, Map<UUID, Integer> votes, BigDecimal agreement, boolean tieBroken, DecidedBy decidedBy) {}
