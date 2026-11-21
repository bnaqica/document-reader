package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.InboxMessage;
import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, UUID> {

    /** Returns 1 when inserted, 0 when a message with this id is already in the inbox. */
    @Modifying
    @Query(value = """
            INSERT INTO document_reader.inbox_message
                (id, message_type, aggregate_id, payload, status, attempts, next_attempt_at, created_at)
            VALUES (:id, :type, :aggregateId, CAST(:payload AS jsonb), 'PENDING', 0, :dueAt, :createdAt)
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("type") String type,
            @Param("aggregateId") UUID aggregateId,
            @Param("payload") String payload,
            @Param("dueAt") Instant dueAt,
            @Param("createdAt") Instant createdAt);

    /**
     * Locks up to {@code limit} messages of one type that are due, or whose lease has expired, in due order. Rows
     * another worker holds are skipped, not waited for.
     */
    @Query(value = """
            SELECT * FROM document_reader.inbox_message
            WHERE message_type = :type
              AND ((status = 'PENDING' AND next_attempt_at <= :now)
                   OR (status = 'PROCESSING' AND locked_until < :now))
            ORDER BY next_attempt_at, created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<InboxMessage> lockDue(@Param("type") String type, @Param("now") Instant now, @Param("limit") int limit);

    /*
     * The transitions below are single conditional UPDATEs, so none can lose a concurrent claim's changes. Those that
     * end a claim apply only to that claim: same holder and same attempt, still PROCESSING. A stale holder (its lease
     * expired and another instance reclaimed the message) or a message already finished changes nothing.
     */

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE InboxMessage m
            SET m.status = :done, m.completedAt = :now, m.lockedBy = null, m.lockedUntil = null
            WHERE m.id = :id AND m.status IN :open
            """)
    int markDone(
            @Param("id") UUID id,
            @Param("done") InboxStatus done,
            @Param("open") List<InboxStatus> open,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE InboxMessage m
            SET m.status = :pending, m.lastError = :error, m.nextAttemptAt = :next,
                m.lockedBy = null, m.lockedUntil = null
            WHERE m.id = :id AND m.status = :processing AND m.lockedBy = :lockedBy AND m.attempts = :attempts
            """)
    int markRetry(
            @Param("id") UUID id,
            @Param("lockedBy") String lockedBy,
            @Param("attempts") int attempts,
            @Param("pending") InboxStatus pending,
            @Param("processing") InboxStatus processing,
            @Param("error") String error,
            @Param("next") Instant next);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE InboxMessage m
            SET m.status = :failed, m.lastError = :error, m.completedAt = :now, m.lockedBy = null, m.lockedUntil = null
            WHERE m.id = :id AND m.status = :processing AND m.lockedBy = :lockedBy AND m.attempts = :attempts
            """)
    int markFailed(
            @Param("id") UUID id,
            @Param("lockedBy") String lockedBy,
            @Param("attempts") int attempts,
            @Param("failed") InboxStatus failed,
            @Param("processing") InboxStatus processing,
            @Param("error") String error,
            @Param("now") Instant now);

    /** Puts a claim back as due now, and takes back the attempt the claim counted. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE InboxMessage m
            SET m.status = :pending, m.attempts = m.attempts - 1, m.nextAttemptAt = :now,
                m.lockedBy = null, m.lockedUntil = null
            WHERE m.id = :id AND m.status = :processing AND m.lockedBy = :lockedBy AND m.attempts = :attempts
            """)
    int release(
            @Param("id") UUID id,
            @Param("lockedBy") String lockedBy,
            @Param("attempts") int attempts,
            @Param("pending") InboxStatus pending,
            @Param("processing") InboxStatus processing,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE InboxMessage m SET m.lastError = :error
            WHERE m.id = :id AND m.status = :processing AND m.lockedBy = :lockedBy AND m.attempts = :attempts
            """)
    int recordError(
            @Param("id") UUID id,
            @Param("lockedBy") String lockedBy,
            @Param("attempts") int attempts,
            @Param("processing") InboxStatus processing,
            @Param("error") String error);

    @Query("""
            SELECT m FROM InboxMessage m
            WHERE (:type IS NULL OR m.messageType = :type) AND (:status IS NULL OR m.status = :status)
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<InboxMessage> find(@Param("type") MessageType type, @Param("status") InboxStatus status, Limit limit);

    @Query(value = "SELECT 1 FROM (SELECT pg_notify('inbox_ready', :type)) AS sent", nativeQuery = true)
    int notifyInboxReady(@Param("type") String type);
}
