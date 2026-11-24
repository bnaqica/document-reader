package com.bnaqica.documentreader.exception;

/** A {@code webhookUrl} that isn't an absolute http or https URL. Answered with 400. */
public class InvalidWebhookUrlException extends RuntimeException {

    public InvalidWebhookUrlException() {
        super("webhookUrl must be an absolute http or https URL");
    }
}
