package com.bnaqica.documentreader.client;

/**
 * Stores and reads the bytes of uploaded files and rendered pages. Every object is encrypted at rest. Every S3
 * failure, including reading a key that doesn't exist, is a {@link com.bnaqica.documentreader.exception.StorageException}.
 * Build keys with {@link com.bnaqica.documentreader.processors.S3Keys}.
 *
 * <p>The service's AWS role needs {@code s3:GetObject}, {@code s3:PutObject} and {@code s3:ListBucket} on the bucket,
 * plus {@code kms:GenerateDataKey} and {@code kms:Decrypt} for SSE-KMS. Without {@code s3:ListBucket}, S3 answers a
 * missing key with 403, and {@link #exists} throws instead of returning false.
 */
public interface DocumentStorageClient {

    /** Stores the bytes, replacing any object already under the key. */
    void put(String key, byte[] bytes, String contentType);

    byte[] get(String key);

    /**
     * Asks without reading the object. A HEAD request carries no error body, so false also covers a bucket that
     * doesn't exist; the next {@link #put} then fails.
     */
    boolean exists(String key);
}
