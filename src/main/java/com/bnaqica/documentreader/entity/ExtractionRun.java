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
import org.hibernate.annotations.ColumnTransformer;

/** One extraction answer for a document at one stage. */
@Entity
@Table(schema = "document_reader", name = "extraction_run")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class ExtractionRun extends InsertOnlyEntity<UUID> {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExtractionStage stage;

    @Column(name = "pass_number", nullable = false)
    private int passNumber;

    /** JSON in the order it was saved (a JSON column, not JSONB). */
    @ColumnTransformer(write = "?::json")
    @Column(nullable = false, columnDefinition = "json")
    private String data;

    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
