package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.ClassificationResultRecord;
import com.bnaqica.documentreader.dto.response.ClassificationRunRecord;
import com.bnaqica.documentreader.dto.response.ExtractionResultRecord;
import com.bnaqica.documentreader.dto.response.ExtractionRunRecord;
import com.bnaqica.documentreader.dto.response.OpenAiCallLogRecord;
import com.bnaqica.documentreader.dto.response.SeparationRunRecord;
import com.bnaqica.documentreader.dto.response.WebhookAttemptRecord;
import com.bnaqica.documentreader.entity.ExtractionStage;
import java.util.List;
import java.util.UUID;

/**
 * Read access to runs, results, webhook attempts and the OpenAI call log, for the caller who sent the batch and for
 * admins. Another caller's record, and a result that doesn't exist yet, throw {@code ResourceNotFoundException}.
 */
public interface RunRecordQueryService {

    List<SeparationRunRecord> separationRuns(UUID sourceFileId, Caller caller);

    List<ClassificationRunRecord> classificationRuns(UUID documentId, Caller caller);

    ClassificationResultRecord classificationResult(UUID documentId, Caller caller);

    /** {@code stage} null means every stage. */
    List<ExtractionRunRecord> extractionRuns(UUID documentId, ExtractionStage stage, Caller caller);

    ExtractionResultRecord extractionResult(UUID documentId, Caller caller);

    List<WebhookAttemptRecord> webhookAttempts(UUID batchId, Caller caller);

    List<OpenAiCallLogRecord> openAiCalls(UUID batchId, Caller caller);
}
