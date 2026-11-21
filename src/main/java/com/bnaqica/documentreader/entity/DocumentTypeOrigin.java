package com.bnaqica.documentreader.entity;

/** Where a document type came from. */
public enum DocumentTypeOrigin {
    /** Shipped with the service in a seed migration. */
    SEEDED,
    /** Added by an admin through the API. */
    MANUAL,
    /** Created during classification, when the model named a type the catalog didn't have. */
    MODEL
}
