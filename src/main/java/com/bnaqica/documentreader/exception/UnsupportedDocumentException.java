package com.bnaqica.documentreader.exception;

/** The upload isn't one of the supported types (PDF, PNG, JPEG, HEIC/HEIF, TIFF, WEBP). Intake answers 415. */
public class UnsupportedDocumentException extends RuntimeException {

    public UnsupportedDocumentException(String detectedMimeType) {
        super("Unsupported content type " + detectedMimeType
                + "; supported are PDF, PNG, JPEG, HEIC/HEIF, TIFF and WEBP");
    }
}
