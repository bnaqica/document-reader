package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.entity.Batch;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.WebhookStatus;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.repository.BatchRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BatchServiceImpl implements BatchService {

    private static final String RESOURCE = "Batch";

    private final BatchRepository repository;
    private final LoanPurposeService loanPurposes;
    private final Clock clock;

    @Override
    @Transactional
    public BatchRecord create(NewBatch request) {
        loanPurposes.get(request.loanPurposeCode());
        Batch batch = Batch.builder()
                .id(UUID.randomUUID())
                .loanPurposeCode(request.loanPurposeCode())
                .async(request.async())
                .webhookUrl(request.webhookUrl())
                .status(BatchStatus.RECEIVED)
                .webhookStatus(request.async() ? WebhookStatus.PENDING : WebhookStatus.NOT_APPLICABLE)
                .fileCount(request.fileCount())
                .requestedBy(request.requestedBy())
                .receivedAt(clock.instant())
                .build();
        repository.save(batch);
        log.info("Created batch {} with {} files", batch.getId(), batch.getFileCount());
        return toRecord(batch);
    }

    @Override
    public BatchRecord get(UUID id) {
        return toRecord(find(id));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyCompleted(UUID id) {
        repository.notifyCompleted(id);
        log.info("Queued the completion notice for batch {}; it is sent on commit", id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BatchRecord lockForUpdate(UUID id) {
        return toRecord(
                repository.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id)));
    }

    @Override
    @Transactional
    public void updateStatus(UUID id, BatchStatus status, String failureReason) {
        find(id).updateStatus(status, failureReason);
        log.info("Batch {} is now {}", id, status);
    }

    @Override
    @Transactional
    public void complete(UUID id, BatchStatus finalStatus, String failureReason, Instant completedAt) {
        find(id).complete(finalStatus, failureReason, completedAt);
        log.info("Completed batch {} as {}", id, finalStatus);
    }

    @Override
    @Transactional
    public void updateWebhookStatus(UUID id, WebhookStatus status) {
        find(id).updateWebhookStatus(status);
        log.info("Batch {} webhook is now {}", id, status);
    }

    @Override
    public List<BatchRecord> findRecent(String requestedBy, int limit) {
        List<Batch> batches = requestedBy == null
                ? repository.findAllByOrderByReceivedAtDescIdDesc(Limit.of(limit))
                : repository.findByRequestedByOrderByReceivedAtDescIdDesc(requestedBy, Limit.of(limit));
        return batches.stream().map(BatchServiceImpl::toRecord).toList();
    }

    private Batch find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    static BatchRecord toRecord(Batch batch) {
        return BatchRecord.builder()
                .id(batch.getId())
                .loanPurposeCode(batch.getLoanPurposeCode())
                .async(batch.isAsync())
                .webhookUrl(batch.getWebhookUrl())
                .status(batch.getStatus())
                .webhookStatus(batch.getWebhookStatus())
                .failureReason(batch.getFailureReason())
                .fileCount(batch.getFileCount())
                .requestedBy(batch.getRequestedBy())
                .receivedAt(batch.getReceivedAt())
                .completedAt(batch.getCompletedAt())
                .build();
    }
}
