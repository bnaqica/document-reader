package com.bnaqica.documentreader.processors;

/** An upload's real type, from its bytes, with the MIME type and the file extension the service stores it under. */
public enum DetectedType {
    PDF("application/pdf", "pdf"),
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    HEIC("image/heic", "heic"),
    TIFF("image/tiff", "tiff"),
    WEBP("image/webp", "webp");

    private final String mimeType;
    private final String extension;

    DetectedType(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }
}
