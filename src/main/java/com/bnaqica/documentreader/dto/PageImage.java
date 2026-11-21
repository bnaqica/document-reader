package com.bnaqica.documentreader.dto;

/** One page image produced by conversion, as it is recorded. */
public record PageImage(int pageNumber, String s3Key, int width, int height) {}
