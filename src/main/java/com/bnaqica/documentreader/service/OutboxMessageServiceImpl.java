package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.response.OutboxMessageRecord;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.entity.OutboxMessage;
import com.bnaqica.documentreader.repository.OutboxMessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OutboxMessageServiceImpl implements OutboxMessageService {

    private final OutboxMessageRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxMessageRecord create(MessageType type, UUID aggregateId, JsonNode payload) {
        OutboxMessage saved = repository.save(OutboxMessage.builder()
                .id(UUID.randomUUID())
                .messageType(type)
                .aggregateId(aggregateId)
                .payload(jsonMapper.writeValueAsString(payload))
                .createdAt(clock.instant())
                .build());
        repository.notifyOutboxReady();
        log.info("Published {} message {} for {}", type, saved.getId(), aggregateId);
        return toRecord(saved);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OutboxMessageRecord> lockUndispatched(int limit) {
        return repository.lockUndispatched(limit).stream().map(this::toRecord).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markDispatched(Collection<UUID> ids, Instant at) {
        repository.markDispatched(ids, at);
    }

    @Override
    public List<OutboxMessageRecord> findRecent(int limit) {
        return repository.findAllByOrderByCreatedAtDescIdDesc(Limit.of(limit)).stream()
                .map(this::toRecord)
                .toList();
    }

    private OutboxMessageRecord toRecord(OutboxMessage message) {
        return OutboxMessageRecord.builder()
                .id(message.getId())
                .messageType(message.getMessageType())
                .aggregateId(message.getAggregateId())
                .payload(jsonMapper.readTree(message.getPayload()))
                .createdAt(message.getCreatedAt())
                .dispatchedAt(message.getDispatchedAt())
                .build();
    }
}
