package com.bnaqica.documentreader.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A new loan purpose. The code is permanent: batches refer to it. */
public record LoanPurposeCreateRequest(
        @NotBlank
        @Pattern(
                regexp = "^[A-Z][A-Z0-9_]{0,49}$",
                message = "must be upper-case letters, digits and underscores, start with a letter, "
                        + "and be at most 50 characters")
        String code,

        @NotBlank @Size(max = 100) String name,
        @NotBlank String description) {}
