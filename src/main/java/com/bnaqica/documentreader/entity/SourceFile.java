package com.bnaqica.documentreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One uploaded file in a batch. {@code fileIndex} is its upload order. */
@Entity
@Table(schema = "document_reader", name = "source_file")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class SourceFile {

    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "file_index", nullable = false)
    private int fileIndex;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "s3_bucket")
    private String s3Bucket;

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceFileStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    /** Null until first saved, so Spring Data inserts a new row instead of merging (no extra SELECT). */
    @Version
    private Long version;

    public void markStored(String s3Bucket, String s3Key) {
        this.s3Bucket = s3Bucket;
        this.s3Key = s3Key;
        this.status = SourceFileStatus.STORED;
    }

    /** The reason is kept only with {@code FAILED}. */
    public void updateStatus(SourceFileStatus status, String failureReason) {
        this.status = status;
        this.failureReason = status == SourceFileStatus.FAILED ? failureReason : null;
    }
}
