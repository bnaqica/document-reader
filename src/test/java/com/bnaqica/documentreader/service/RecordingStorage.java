package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.client.DocumentStorageClient;
import com.bnaqica.documentreader.exception.StorageException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * An in-memory {@link DocumentStorageClient} for intake tests. It keeps what was put, can run a check at each put
 * (to see the database as it is then), and can be told to fail every put the way S3 would.
 */
public class RecordingStorage implements DocumentStorageClient {

    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private final Map<String, String> contentTypes = new LinkedHashMap<>();
    private Consumer<String> onPut = key -> {};
    private RuntimeException failure;

    @Override
    public synchronized void put(String key, byte[] bytes, String contentType) {
        onPut.accept(key);
        if (failure != null) {
            throw failure;
        }
        objects.put(key, bytes.clone());
        contentTypes.put(key, contentType);
    }

    @Override
    public synchronized byte[] get(String key) {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new StorageException("get", key, new RuntimeException("No such key"));
        }
        return bytes.clone();
    }

    @Override
    public synchronized boolean exists(String key) {
        return objects.containsKey(key);
    }

    public synchronized Map<String, byte[]> objects() {
        return Map.copyOf(objects);
    }

    public synchronized String contentType(String key) {
        return contentTypes.get(key);
    }

    /** Runs before each put is stored (or refused). */
    public synchronized void onPut(Consumer<String> check) {
        this.onPut = check;
    }

    public synchronized void failEveryPut() {
        failEveryPutWith(new StorageException("put", "files/any", new RuntimeException("S3 is down")));
    }

    /** Any other failure, such as one the S3 client doesn't wrap. */
    public synchronized void failEveryPutWith(RuntimeException failure) {
        this.failure = failure;
    }

    public synchronized void reset() {
        objects.clear();
        contentTypes.clear();
        onPut = key -> {};
        failure = null;
    }
}
