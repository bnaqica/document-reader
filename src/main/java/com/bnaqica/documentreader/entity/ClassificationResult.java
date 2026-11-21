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
import org.hibernate.annotations.ColumnTransformer;

/** The type a document was classified as, keyed by the document: one per document. */
@Entity
@Table(schema = "document_reader", name = "classification_result")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class ClassificationResult extends InsertOnlyEntity<UUID> {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    /** JSON object of type id to vote count. */
    @ColumnTransformer(write = "?::json")
    @Column(nullable = false, columnDefinition = "json")
    private String votes;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal agreement;

    @Column(name = "tie_broken", nullable = false)
    private boolean tieBroken;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by", nullable = false)
    private DecidedBy decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Override
    public UUID getId() {
        return documentId;
    }
}
