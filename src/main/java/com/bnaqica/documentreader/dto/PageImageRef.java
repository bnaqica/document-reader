package com.bnaqica.documentreader.dto;

/** A rendered PNG page, by its S3 key; the vision client reads the bytes itself. */
public record PageImageRef(int pageNumber, String s3Key) {}
