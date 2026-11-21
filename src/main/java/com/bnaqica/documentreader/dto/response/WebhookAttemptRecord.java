package com.bnaqica.documentreader.dto.response;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record WebhookAttemptRecord(UUID batchId, int attempt, Integer httpStatus, String error, Instant attemptedAt) {}
