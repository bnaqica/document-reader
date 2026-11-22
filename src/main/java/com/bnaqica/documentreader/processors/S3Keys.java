package com.bnaqica.documentreader.processors;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The S3 key layout: {@code {prefix}{sourceFileId}/original.{ext}} and {@code {prefix}{sourceFileId}/pages/{nnnn}.png}.
 * Keys hold only ids, page numbers and an extension, so a file name can never end up in a key.
 */
public class S3Keys {

    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,10}");

    /** {@code file.max-pages} is 100, so four digits leave plenty of room. */
    private static final int MAX_PAGE = 9999;

    private final String prefix;

    public S3Keys(String prefix) {
        this.prefix = prefix;
    }

    /** @throws IllegalArgumentException for an extension that isn't 1–10 letters or digits */
    public String original(UUID sourceFileId, String ext) {
        String extension = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (!EXTENSION.matcher(extension).matches()) {
            throw new IllegalArgumentException("Not a file extension: must be 1-10 letters or digits");
        }
        return prefix + sourceFileId + "/original." + extension;
    }

    /** @throws IllegalArgumentException for a page outside 1–9999 */
    public String page(UUID sourceFileId, int page) {
        if (page < 1 || page > MAX_PAGE) {
            throw new IllegalArgumentException("Page " + page + " is outside 1-" + MAX_PAGE);
        }
        return prefix + sourceFileId + "/pages/" + String.format(Locale.ROOT, "%04d", page) + ".png";
    }
}
