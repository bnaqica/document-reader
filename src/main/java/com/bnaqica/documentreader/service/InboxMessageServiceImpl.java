package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.dto.response.OutboxMessageRecord;
import com.bnaqica.documentreader.entity.InboxMessage;
import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.repository.InboxMessageRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InboxMessageServiceImpl implements InboxMessageService {

    private static final String RESOURCE = "Inbox message";

    /** Enough to diagnose; a stack-trace-sized message can't bloat a row. */
    static final int MAX_ERROR_LENGTH = 2000;

    private final InboxMessageRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MessageType> insertIfAbsent(List<OutboxMessageRecord> messages, Instant dueAt) {
        Set<MessageType> inserted = EnumSet.noneOf(MessageType.class);
        for (OutboxMessageRecord message : messages) {
            int rows = repository.insertIfAbsent(
                    message.id(),
                    message.messageType().name(),
                    message.aggregateId(),
                    jsonMapper.writeValueAsString(message.payload()),
                    dueAt,
                    message.createdAt());
            if (rows == 1) {
                inserted.add(message.messageType());
            } else {
                log.debug("Inbox already holds message {}; skipped", message.id());
            }
        }
        inserted.forEach(type -> repository.notifyInboxReady(type.name()));
        return List.copyOf(inserted);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<InboxMessageRecord> claimDue(MessageType type, int limit, String instanceId, Duration lease) {
        Instant now = clock.instant();
        List<InboxMessage> claimed = repository.lockDue(type.name(), now, limit);
        claimed.forEach(message -> message.claim(instanceId, now.plus(lease)));
        repository.flush();
        if (!claimed.isEmpty()) {
            log.info("Claimed {} {} messages for {}", claimed.size(), type, instanceId);
        }
        return claimed.stream().map(this::toRecord).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markDone(UUID id) {
        int updated = repository.markDone(
                id, InboxStatus.DONE, List.of(InboxStatus.PENDING, InboxStatus.PROCESSING), clock.instant());
        if (updated == 1) {
            log.info("Inbox message {} done", id);
        } else {
            log.debug("Inbox message {} was already finished; not marked done", id);
        }
    }

    @Override
    @Transactional
    public boolean markRetry(InboxMessageRecord claim, String error, Instant nextAttemptAt) {
        boolean updated = repository.markRetry(
                        claim.id(),
                        claim.lockedBy(),
                        claim.attempts(),
                        InboxStatus.PENDING,
                        InboxStatus.PROCESSING,
                        truncate(error),
                        nextAttemptAt)
                == 1;
        if (updated) {
            log.info("Inbox message {} failed attempt {}; retrying at {}", claim.id(), claim.attempts(), nextAttemptAt);
        } else {
            log.info("Inbox message {} is no longer this claim's; retry not scheduled", claim.id());
        }
        return updated;
    }

    @Override
    @Transactional
    public boolean markFailed(InboxMessageRecord claim, String error) {
        boolean updated = repository.markFailed(
                        claim.id(),
                        claim.lockedBy(),
                        claim.attempts(),
                        InboxStatus.FAILED,
                        InboxStatus.PROCESSING,
                        truncate(error),
                        clock.instant())
                == 1;
        if (updated) {
            log.error("Inbox message {} failed for good after {} attempts", claim.id(), claim.attempts());
        } else {
            log.info("Inbox message {} is no longer this claim's; not failed", claim.id());
        }
        return updated;
    }

    @Override
    @Transactional
    public boolean release(InboxMessageRecord claim) {
        boolean updated = repository.release(
                        claim.id(),
                        claim.lockedBy(),
                        claim.attempts(),
                        InboxStatus.PENDING,
                        InboxStatus.PROCESSING,
                        clock.instant())
                == 1;
        if (updated) {
            log.info("Released inbox message {} without counting attempt {}", claim.id(), claim.attempts());
        }
        return updated;
    }

    @Override
    @Transactional
    public boolean recordError(InboxMessageRecord claim, String error) {
        return repository.recordError(
                        claim.id(), claim.lockedBy(), claim.attempts(), InboxStatus.PROCESSING, truncate(error))
                == 1;
    }

    @Override
    public InboxMessageRecord get(UUID id) {
        return toRecord(find(id));
    }

    @Override
    public List<InboxMessageRecord> find(MessageType type, InboxStatus status, int limit) {
        return repository.find(type, status, Limit.of(limit)).stream()
                .map(this::toRecord)
                .toList();
    }

    private InboxMessage find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private InboxMessageRecord toRecord(InboxMessage message) {
        return InboxMessageRecord.builder()
                .id(message.getId())
                .messageType(message.getMessageType())
                .aggregateId(message.getAggregateId())
                .payload(jsonMapper.readTree(message.getPayload()))
                .status(message.getStatus())
                .attempts(message.getAttempts())
                .nextAttemptAt(message.getNextAttemptAt())
                .lockedBy(message.getLockedBy())
                .lockedUntil(message.getLockedUntil())
                .lastError(message.getLastError())
                .createdAt(message.getCreatedAt())
                .completedAt(message.getCompletedAt())
                .build();
    }
}
