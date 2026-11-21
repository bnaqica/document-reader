package com.bnaqica.documentreader.entity;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * A row written once and never changed, with an assigned id. {@link #isNew()} tells Spring Data to insert a built
 * entity rather than merge it, so saving costs no SELECT and a duplicate key fails on insert instead of updating.
 */
@MappedSuperclass
public abstract class InsertOnlyEntity<I> implements Persistable<I> {

    @Transient
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
