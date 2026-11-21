package com.bnaqica.documentreader.dto;

/** One uploaded file as intake records it. Its index comes from its position in the batch's list. */
public record NewSourceFile(
        String originalFilename, String contentType, long sizeBytes, String sha256, int pageCount) {}
