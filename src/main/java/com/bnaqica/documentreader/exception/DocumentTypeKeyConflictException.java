package com.bnaqica.documentreader.exception;

/** A type or alias name reduces to a key that already belongs to a document type. Answered with 409. */
public class DocumentTypeKeyConflictException extends RuntimeException {

    /** {@code ownerName} is null when the owner isn't known, such as when a concurrent request won the race. */
    public DocumentTypeKeyConflictException(String key, String ownerName) {
        super(
                ownerName == null
                        ? "The name reduces to '" + key + "', which already belongs to a document type"
                        : "The name reduces to '" + key + "', which already belongs to the document type \"" + ownerName
                                + "\"");
    }
}
