package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.response.WebhookAttemptRecord;
import com.bnaqica.documentreader.entity.WebhookDeliveryAttempt;
import com.bnaqica.documentreader.repository.WebhookDeliveryAttemptRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WebhookDeliveryAttemptServiceImpl implements WebhookDeliveryAttemptService {

    private final WebhookDeliveryAttemptRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public WebhookAttemptRecord record(UUID batchId, int attempt, Integer httpStatus, String error) {
        WebhookDeliveryAttempt saved = repository.saveAndFlush(WebhookDeliveryAttempt.builder()
                .id(UUID.randomUUID())
                .batchId(batchId)
                .attempt(attempt)
                .httpStatus(httpStatus)
                .error(error)
                .attemptedAt(clock.instant())
                .build());
        log.info("Recorded webhook attempt {} for batch {} (status {})", attempt, batchId, httpStatus);
        return toRecord(saved);
    }

    @Override
    public List<WebhookAttemptRecord> findByBatch(UUID batchId) {
        return repository.findByBatchIdOrderByAttemptAsc(batchId).stream()
                .map(WebhookDeliveryAttemptServiceImpl::toRecord)
                .toList();
    }

    private static WebhookAttemptRecord toRecord(WebhookDeliveryAttempt attempt) {
        return WebhookAttemptRecord.builder()
                .batchId(attempt.getBatchId())
                .attempt(attempt.getAttempt())
                .httpStatus(attempt.getHttpStatus())
                .error(attempt.getError())
                .attemptedAt(attempt.getAttemptedAt())
                .build();
    }
}
