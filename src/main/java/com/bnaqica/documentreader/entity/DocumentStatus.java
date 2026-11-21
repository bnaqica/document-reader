package com.bnaqica.documentreader.entity;

/** Where one document is: waiting, being classified, classified, being extracted, done, or failed. */
public enum DocumentStatus {
    PENDING,
    CLASSIFYING,
    CLASSIFIED,
    EXTRACTING,
    COMPLETED,
    FAILED;

    /** Completed or failed: the document needs no more work. */
    public boolean isFinished() {
        return this == COMPLETED || this == FAILED;
    }
}
