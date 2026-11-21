package com.bnaqica.documentreader.exception;

/** Page ranges for a file's documents are outside the file, reversed, or overlapping. A pipeline bug, not a 4xx. */
public class InvalidPageRangesException extends IllegalArgumentException {

    public InvalidPageRangesException(String message) {
        super(message);
    }
}
