package com.bnaqica.documentreader.exception;

/** Documents refer to this document type, so it can't be removed. Answered with 409. */
public class DocumentTypeInUseException extends RuntimeException {

    public DocumentTypeInUseException(String typeName) {
        this(typeName, null);
    }

    public DocumentTypeInUseException(String typeName, Throwable cause) {
        super("The document type \"" + typeName + "\" is in use by documents and can't be removed", cause);
    }
}
