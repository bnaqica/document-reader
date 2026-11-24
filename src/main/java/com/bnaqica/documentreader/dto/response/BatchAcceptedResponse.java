package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.BatchStatus;
import java.util.UUID;

/** The 202 answer: the batch is queued (or still processing), and its result will be at {@code statusUrl}. */
public record BatchAcceptedResponse(UUID batchId, BatchStatus status, String statusUrl) {}
