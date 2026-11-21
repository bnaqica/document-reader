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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One OpenAI call, for cost and latency reporting. It never holds the prompt or the response, because those carry
 * borrower data.
 */
@Entity
@Table(schema = "document_reader_integration", name = "openai_call")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class OpenAiCall extends InsertOnlyEntity<UUID> {

    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "source_file_id", nullable = false)
    private UUID sourceFileId;

    /** Null for separation, which works on a whole file. */
    @Column(name = "document_id")
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OpenAiCallPurpose purpose;

    @Column(nullable = false)
    private String model;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "http_status")
    private Integer httpStatus;

    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
