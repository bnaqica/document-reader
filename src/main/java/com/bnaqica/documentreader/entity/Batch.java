package com.bnaqica.documentreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One request: one or more files sent together, with a loan purpose. */
@Entity
@Table(schema = "document_reader", name = "batch")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class Batch {

    @Id
    private UUID id;

    @Column(name = "loan_purpose_code", nullable = false)
    private String loanPurposeCode;

    @Column(nullable = false)
    private boolean async;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BatchStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "webhook_status", nullable = false)
    private WebhookStatus webhookStatus;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Null until first saved, so Spring Data inserts a new row instead of merging (no extra SELECT). */
    @Version
    private Long version;

    /** Moves between the statuses that aren't final; finishing goes through {@link #complete} only. */
    public void updateStatus(BatchStatus status, String failureReason) {
        if (status.isFinal()) {
            throw new IllegalArgumentException(status + " is final; complete the batch instead");
        }
        requireNotComplete();
        this.status = status;
        this.failureReason = null;
    }

    /** Records the final status once; a batch is never completed twice. The reason is kept only with {@code FAILED}. */
    public void complete(BatchStatus finalStatus, String failureReason, Instant completedAt) {
        if (!finalStatus.isFinal()) {
            throw new IllegalArgumentException(finalStatus + " is not a final batch status");
        }
        requireNotComplete();
        this.status = finalStatus;
        this.failureReason = finalStatus == BatchStatus.FAILED ? failureReason : null;
        this.completedAt = completedAt;
    }

    private void requireNotComplete() {
        if (completedAt != null) {
            throw new IllegalStateException("Batch " + id + " is already complete as " + status);
        }
    }

    public void updateWebhookStatus(WebhookStatus webhookStatus) {
        this.webhookStatus = webhookStatus;
    }
}
