package com.bnaqica.documentreader.exception;

import java.util.List;
import lombok.Getter;

/** A document type's data schema breaks the catalog's strict rules. Answered with 400 naming {@code dataSchema}. */
@Getter
public class InvalidDataSchemaException extends RuntimeException {

    private final List<String> violations;

    public InvalidDataSchemaException(List<String> violations) {
        super("dataSchema is not a strict data schema: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }
}
