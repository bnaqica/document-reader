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

/** One separation answer for a source file, or for one window of a long file. */
@Entity
@Table(schema = "document_reader", name = "separation_run")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class SeparationRun extends InsertOnlyEntity<UUID> {

    @Id
    private UUID id;

    @Column(name = "source_file_id", nullable = false)
    private UUID sourceFileId;

    @Column(name = "run_number", nullable = false)
    private int runNumber;

    @Column(name = "window_start", nullable = false)
    private int windowStart;

    @Column(name = "window_end", nullable = false)
    private int windowEnd;

    @Column(nullable = false)
    private boolean succeeded;

    /** JSON array of {@code {startPage, label}}; null when the run failed. */
    @ColumnTransformer(write = "?::json")
    @Column(columnDefinition = "json")
    private String boundaries;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
