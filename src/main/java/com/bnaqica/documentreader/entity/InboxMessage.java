package com.bnaqica.documentreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A message a worker claims and processes. Rows are inserted by the dispatcher with native SQL (insert, or do nothing
 * when the id is already there). A claim changes it through {@link #claim}; every other transition is a conditional
 * bulk update in the repository, so it applies only to the claim it belongs to.
 */
@Entity
@Table(schema = "document_reader", name = "inbox_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
public class InboxMessage {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    private MessageType messageType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, columnDefinition = "jsonb", insertable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Takes the message under a lease; each claim counts as one attempt. */
    public void claim(String instanceId, Instant lockedUntil) {
        this.status = InboxStatus.PROCESSING;
        this.lockedBy = instanceId;
        this.lockedUntil = lockedUntil;
        this.attempts++;
    }
}
