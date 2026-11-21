package com.bnaqica.documentreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One classification answer for a document, and the catalog type it resolved to (none if it failed). */
@Entity
@Table(schema = "document_reader", name = "classification_run")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class ClassificationRun extends InsertOnlyEntity<UUID> {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClassificationRound round;

    @Column(name = "run_number", nullable = false)
    private int runNumber;

    /** The type name the model gave, before resolving it against the catalog. */
    @Column(name = "raw_answer")
    private String rawAnswer;

    @Column(name = "document_type_id")
    private UUID documentTypeId;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    private String reasoning;

    @Column(nullable = false)
    private boolean succeeded;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
