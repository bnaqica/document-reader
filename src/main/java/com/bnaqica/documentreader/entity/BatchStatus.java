package com.bnaqica.documentreader.entity;

/** Where a batch is in the pipeline. The last three are final: a batch reaches one of them exactly once. */
public enum BatchStatus {
    RECEIVED,
    PROCESSING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED;

    public boolean isFinal() {
        return this == COMPLETED || this == PARTIALLY_COMPLETED || this == FAILED;
    }
}
