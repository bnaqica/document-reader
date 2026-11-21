package com.bnaqica.documentreader.exception;

/** The requested resource doesn't exist. Answered with 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object id) {
        super(resource + " " + id + " was not found");
    }
}
