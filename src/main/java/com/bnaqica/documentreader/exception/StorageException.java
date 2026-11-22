package com.bnaqica.documentreader.exception;

/** An S3 operation failed. Names the operation and the key, which holds only ids; never the object's bytes. */
public class StorageException extends RuntimeException {

    public StorageException(String operation, String key, Throwable cause) {
        super("S3 " + operation + " failed for " + key, cause);
    }
}
