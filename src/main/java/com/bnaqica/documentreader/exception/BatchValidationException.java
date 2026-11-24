package com.bnaqica.documentreader.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A batch was refused before anything was stored. The status and detail describe the whole request; {@code errors}
 * lists each failing file and is empty when the request itself was refused.
 */
public class BatchValidationException extends RuntimeException {

    private final HttpStatus status;
    private final List<FileError> errors;

    public BatchValidationException(HttpStatus status, String detail, List<FileError> errors) {
        super(detail);
        this.status = status;
        this.errors = List.copyOf(errors);
    }

    public BatchValidationException(HttpStatus status, String detail) {
        this(status, detail, List.of());
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<FileError> getErrors() {
        return errors;
    }

    /** One failing file, named by its index and file name; returned only to the caller who sent it, never logged. */
    public record FileError(int index, String fileName, int status, String detail) {}
}
