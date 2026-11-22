package com.bnaqica.documentreader.config;

import com.bnaqica.documentreader.client.DocumentStorageClient;
import com.bnaqica.documentreader.exception.StorageException;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** {@link DocumentStorageClient} over the AWS SDK. Every SDK error becomes a {@link StorageException}. */
@Slf4j
public class S3DocumentStorageClient implements DocumentStorageClient {

    private final S3Client s3;
    private final String bucket;
    private final ServerSideEncryption encryption;

    public S3DocumentStorageClient(S3Client s3, String bucket, String serverSideEncryption) {
        this.s3 = s3;
        this.bucket = bucket;
        this.encryption = ServerSideEncryption.fromValue(serverSideEncryption);
        if (encryption == null || encryption == ServerSideEncryption.UNKNOWN_TO_SDK_VERSION) {
            throw new IllegalArgumentException("Unknown S3 server-side encryption: " + serverSideEncryption);
        }
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            s3.putObject(
                    request -> request.bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .serverSideEncryption(encryption),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw new StorageException("put", key, e);
        }
        log.info("Stored {} ({} bytes)", key, bytes.length);
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(request -> request.bucket(bucket).key(key))
                    .asByteArray();
        } catch (SdkException e) {
            throw new StorageException("get", key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(request -> request.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (SdkException e) {
            throw new StorageException("exists", key, e);
        }
    }
}
