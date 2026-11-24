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

/** One logical document found in a source file, covering pages {@code startPage} to {@code endPage}. */
@Entity
@Table(schema = "document_reader", name = "document")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class Document {

    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "source_file_id", nullable = false)
    private UUID sourceFileId;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "start_page", nullable = false)
    private int startPage;

    @Column(name = "end_page", nullable = false)
    private int endPage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Column(name = "document_type_id")
    private UUID documentTypeId;

    @Column(nullable = false)
    private boolean truncated;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Null until first saved, so Spring Data inserts a new row instead of merging (no extra SELECT). */
    @Version
    private Long version;

    /** The reason is kept only with {@code FAILED}; the time only while finished (completed or failed). */
    public void updateStatus(DocumentStatus status, String failureReason, Instant now) {
        this.status = status;
        this.failureReason = status == DocumentStatus.FAILED ? failureReason : null;
        this.completedAt = status.isFinished() ? now : null;
    }

    /** Extraction read only the first pages. */
    public void markTruncated() {
        this.truncated = true;
    }

    public void assignType(UUID documentTypeId) {
        this.documentTypeId = documentTypeId;
    }
}
