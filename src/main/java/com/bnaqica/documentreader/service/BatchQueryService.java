package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.BatchResultResponse;
import java.util.UUID;

/** Builds a batch's result envelope (§7.3) at any stage. */
public interface BatchQueryService {

    /**
     * @throws com.bnaqica.documentreader.exception.ResourceNotFoundException for an unknown batch, and for another
     *     caller's batch unless the caller is an admin, so the answer doesn't reveal that it exists
     */
    BatchResultResponse getResult(UUID batchId, Caller caller);
}
