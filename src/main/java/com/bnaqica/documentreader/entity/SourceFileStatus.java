package com.bnaqica.documentreader.entity;

/** Where an uploaded file is: received, stored in S3, being split into documents, split, or failed. */
public enum SourceFileStatus {
    RECEIVED,
    STORED,
    SEPARATING,
    SEPARATED,
    FAILED
}
