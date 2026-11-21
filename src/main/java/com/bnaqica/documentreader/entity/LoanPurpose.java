package com.bnaqica.documentreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 * What a batch's loan is for. Never deleted, only deactivated, because batches keep referring to the code.
 *
 * <p>The code is the id and is set by the caller, so Spring Data can't tell a new purpose from an existing one and
 * would merge over it. {@link #isNew()} makes {@code save} insert instead, so a taken code fails on the primary key.
 */
@Entity
@Table(schema = "document_reader", name = "loan_purpose")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class LoanPurpose implements Persistable<String> {

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private boolean active;

    /** True only for a purpose built to be created; loaded and saved purposes are never new. */
    @Transient
    @Getter(AccessLevel.NONE)
    @Builder.Default
    private boolean newEntity = false;

    @Override
    public String getId() {
        return code;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    /** Replaces everything except the code. */
    public void update(String name, String description, boolean active) {
        this.name = name;
        this.description = description;
        this.active = active;
    }

    public void deactivate() {
        this.active = false;
    }
}
