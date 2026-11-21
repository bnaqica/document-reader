package com.bnaqica.documentreader.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Replaces a loan purpose's name, description and active flag. The code can't change. */
public record LoanPurposeUpdateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank String description,
        @NotNull Boolean active) {}
