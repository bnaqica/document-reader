package com.bnaqica.documentreader.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Another name for a document type. */
public record DocumentTypeAliasRequest(
        @NotBlank @Size(max = 200) String alias) {}
