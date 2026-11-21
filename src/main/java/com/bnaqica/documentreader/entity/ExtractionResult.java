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
import org.hibernate.annotations.ColumnTransformer;

/** A document's final extracted data and summary, keyed by the document: one per document. */
@Entity
@Table(schema = "document_reader", name = "extraction_result")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class ExtractionResult extends InsertOnlyEntity<UUID> {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    /** JSON in the order it was saved (a JSON column, not JSONB). */
    @ColumnTransformer(write = "?::json")
    @Column(nullable = false, columnDefinition = "json")
    private String data;

    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public UUID getId() {
        return documentId;
    }
}
