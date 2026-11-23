package com.bnaqica.documentreader.processors;

/** One page as an RGB PNG, numbered from 1 in document order. */
public record ConvertedPage(int pageNumber, byte[] png, int width, int height) {}
