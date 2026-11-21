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
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Checks access through {@link RecordQueryService}, so the ownership rule lives in one place, then reads. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunRecordQueryServiceImpl implements RunRecordQueryService {

    private final RecordQueryService records;
    private final SeparationRunService separationRuns;
    private final ClassificationRunService classificationRuns;
    private final ClassificationResultService classificationResults;
    private final ExtractionRunService extractionRuns;
    private final ExtractionResultService extractionResults;
    private final WebhookDeliveryAttemptService webhookAttempts;
    private final OpenAiCallService openAiCalls;

    @Override
    public List<SeparationRunRecord> separationRuns(UUID sourceFileId, Caller caller) {
        records.sourceFile(sourceFileId, caller);
        return separationRuns.findBySourceFile(sourceFileId);
    }

    @Override
    public List<ClassificationRunRecord> classificationRuns(UUID documentId, Caller caller) {
        records.document(documentId, caller);
        return classificationRuns.findByDocument(documentId);
    }

    @Override
    public ClassificationResultRecord classificationResult(UUID documentId, Caller caller) {
        records.document(documentId, caller);
        return classificationResults
                .find(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Classification result of document", documentId));
    }

    @Override
    public List<ExtractionRunRecord> extractionRuns(UUID documentId, ExtractionStage stage, Caller caller) {
        records.document(documentId, caller);
        return stage == null
                ? extractionRuns.findByDocument(documentId)
                : extractionRuns.findByDocumentAndStage(documentId, stage);
    }

    @Override
    public ExtractionResultRecord extractionResult(UUID documentId, Caller caller) {
        records.document(documentId, caller);
        return extractionResults
                .find(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Extraction result of document", documentId));
    }

    @Override
    public List<WebhookAttemptRecord> webhookAttempts(UUID batchId, Caller caller) {
        records.batch(batchId, caller);
        return webhookAttempts.findByBatch(batchId);
    }

    @Override
    public List<OpenAiCallLogRecord> openAiCalls(UUID batchId, Caller caller) {
        records.batch(batchId, caller);
        return openAiCalls.findByBatch(batchId);
    }
}
