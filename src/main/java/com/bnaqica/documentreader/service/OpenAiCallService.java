package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.OpenAiCallRecord;
import com.bnaqica.documentreader.dto.response.OpenAiCallLogRecord;
import java.util.List;
import java.util.UUID;

/** The log of OpenAI calls, kept in the integration schema. It never holds prompt or response bodies. */
public interface OpenAiCallService {

    OpenAiCallLogRecord record(OpenAiCallRecord call);

    /** Oldest first. */
    List<OpenAiCallLogRecord> findByBatch(UUID batchId);
}
