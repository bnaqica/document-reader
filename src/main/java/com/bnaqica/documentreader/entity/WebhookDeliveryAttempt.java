package com.bnaqica.documentreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One attempt to deliver a batch's webhook. */
@Entity
@Table(schema = "document_reader", name = "webhook_delivery_attempt")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class WebhookDeliveryAttempt extends InsertOnlyEntity<UUID> {

    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(nullable = false)
    private int attempt;

    /** Null when no response came (a timeout or connection error). */
    @Column(name = "http_status")
    private Integer httpStatus;

    private String error;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;
}
