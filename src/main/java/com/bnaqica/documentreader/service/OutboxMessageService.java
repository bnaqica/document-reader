package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.response.OutboxMessageRecord;
import com.bnaqica.documentreader.entity.MessageType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Outbox rows. Writing and locking them only make sense inside the caller's transaction. */
public interface OutboxMessageService {

    /**
     * Stores the message and queues {@code NOTIFY outbox_ready}, both in the caller's transaction.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException outside a transaction
     */
    OutboxMessageRecord create(MessageType type, UUID aggregateId, JsonNode payload);

    /** Locks up to {@code limit} undispatched rows, oldest first, skipping rows another transaction holds. */
    List<OutboxMessageRecord> lockUndispatched(int limit);

    void markDispatched(Collection<UUID> ids, Instant at);

    /** Newest first. */
    List<OutboxMessageRecord> findRecent(int limit);
}
