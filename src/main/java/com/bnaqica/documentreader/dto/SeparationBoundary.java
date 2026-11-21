package com.bnaqica.documentreader.dto;

/** A page where a separation run starts a new document, with the model's tentative label (not used as the type). */
public record SeparationBoundary(int startPage, String label) {}
