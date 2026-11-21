package com.bnaqica.documentreader.dto;

/** The pages one document covers, first to last, both inclusive and counted from 1. */
public record PageRange(int start, int end) {}
