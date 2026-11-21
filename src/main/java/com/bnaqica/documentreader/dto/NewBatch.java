package com.bnaqica.documentreader.dto;

/** What intake knows when it creates a batch. {@code requestedBy} is the token's subject. */
public record NewBatch(String loanPurposeCode, boolean async, String webhookUrl, String requestedBy, int fileCount) {}
