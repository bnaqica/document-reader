package com.bnaqica.documentreader.exception;

/**
 * A file of a supported type can't be read: password-protected, corrupt, or too large to decode safely. Intake answers
 * 422. The message names the type and the reason, never the file's content.
 */
public class UnreadableDocumentException extends RuntimeException {

    public UnreadableDocumentException(String reason) {
        super(reason);
    }

    public UnreadableDocumentException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
