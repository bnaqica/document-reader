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
import org.hibernate.annotations.ColumnTransformer;

/**
 * A kind of document the catalog classifies into, such as "Pay stubs", with the strict JSON Schema of the data
 * extraction returns for it. The data schema is kept as JSON text in its authored key order; services parse it
 * with Jackson 3.
 */
@Entity
@Table(schema = "document_reader", name = "document_type")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class DocumentType {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "normalized_key", nullable = false)
    private String normalizedKey;

    @Column(nullable = false)
    private String description;

    /**
     * Written with a plain {@code ::json} cast, because Hibernate's JSON type casts to {@code jsonb}, which would
     * reorder the schema's keys before they reach the {@code json} column.
     */
    @ColumnTransformer(write = "?::json")
    @Column(name = "data_schema", nullable = false, columnDefinition = "json")
    private String dataSchema;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentTypeOrigin origin;

    @Column(name = "created_by_document_id")
    private UUID createdByDocumentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    /** Renames the type; the caller supplies the new name's key. */
    public void rename(String name, String normalizedKey) {
        this.name = name;
        this.normalizedKey = normalizedKey;
    }

    public void describe(String description) {
        this.description = description;
    }

    /** Replaces the data schema and moves to the next schema version. */
    public void replaceDataSchema(String dataSchema) {
        this.dataSchema = dataSchema;
        this.schemaVersion++;
    }
}
