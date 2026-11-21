package com.bnaqica.documentreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * One rendered page image of a source file, stored in S3. Its id is assigned, so {@link #isNew()} tells Spring Data
 * to insert a built page rather than merge it.
 */
@Entity
@Table(schema = "document_reader", name = "source_file_page")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class SourceFilePage implements Persistable<SourceFilePageId> {

    @EmbeddedId
    private SourceFilePageId id;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Transient
    @Getter(AccessLevel.NONE)
    @Builder.Default
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }
}
