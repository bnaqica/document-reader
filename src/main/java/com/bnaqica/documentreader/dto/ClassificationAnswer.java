package com.bnaqica.documentreader.dto;

/** One classification or tie-break run's answer. {@code confidence} is from 0 to 1. */
public record ClassificationAnswer(String documentType, double confidence, String reasoning) {}
