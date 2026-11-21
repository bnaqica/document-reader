package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.OutboxMessage;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /** Locks the oldest undispatched rows; rows another dispatcher holds are skipped, not waited for. */
    @Query(value = """
            SELECT * FROM document_reader.outbox_message
            WHERE dispatched_at IS NULL
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> lockUndispatched(@Param("limit") int limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OutboxMessage m SET m.dispatchedAt = :at WHERE m.id IN :ids")
    int markDispatched(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);

    List<OutboxMessage> findAllByOrderByCreatedAtDescIdDesc(Limit limit);

    /** Postgres delivers a NOTIFY only when the transaction commits, and drops it on rollback. */
    @Query(value = "SELECT 1 FROM (SELECT pg_notify('outbox_ready', '')) AS sent", nativeQuery = true)
    int notifyOutboxReady();
}
