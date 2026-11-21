package com.bnaqica.documentreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A page is identified by its file and its number. Value equality is required for an id class. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class SourceFilePageId implements Serializable {

    @Column(name = "source_file_id", nullable = false)
    private UUID sourceFileId;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;
}
