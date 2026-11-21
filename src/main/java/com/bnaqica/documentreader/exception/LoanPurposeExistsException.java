package com.bnaqica.documentreader.exception;

/** A loan purpose with this code already exists. Answered with 409. */
public class LoanPurposeExistsException extends RuntimeException {

    public LoanPurposeExistsException(String code) {
        super("Loan purpose " + code + " already exists");
    }
}
