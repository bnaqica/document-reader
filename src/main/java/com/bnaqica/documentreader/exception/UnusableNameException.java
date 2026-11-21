package com.bnaqica.documentreader.exception;

import lombok.Getter;

/** A type or alias name has nothing usable in it, such as "N/A". Answered with 400 naming the field. */
@Getter
public class UnusableNameException extends RuntimeException {

    private final String field;

    public UnusableNameException(String field) {
        super(field + " must contain letters or digits, and must not be a placeholder such as N/A");
        this.field = field;
    }
}
