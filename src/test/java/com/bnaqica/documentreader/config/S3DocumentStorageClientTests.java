package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.client.DocumentStorageClient;
import com.bnaqica.documentreader.exception.StorageException;
import com.bnaqica.documentreader.processors.S3Keys;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** Against a real S3 API (LocalStack, pinned), through the same client S3Config builds for the application. */
@Testcontainers
class S3DocumentStorageClientTests {

    private static final String BUCKET = "document-reader-test";

    @Container
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4"));

    private static S3Client s3;
    private static DocumentStorageClient storage;

    private static DocumentReaderProperties.S3 settings(String bucket) {
        return new DocumentReaderProperties.S3(
                bucket, "files/", LOCALSTACK.getRegion(), LOCALSTACK.getEndpoint(), true, "AES256");
    }

    @BeforeAll
    static void createBucket() {
        s3 = S3Config.s3Client(settings(BUCKET));
        s3.createBucket(request -> request.bucket(BUCKET));
        storage = new S3DocumentStorageClient(s3, BUCKET, "AES256");
    }

    @AfterAll
    static void closeClient() {
        s3.close();
    }

    private static String key() {
        return "files/" + UUID.randomUUID() + "/original.pdf";
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    @Test
    void putThenGetRoundTrips() {
        String key = key();
        byte[] bytes = randomBytes(3 * 1024 * 1024);

        storage.put(key, bytes, "application/pdf");

        assertThat(storage.get(key)).isEqualTo(bytes);
        assertThat(storage.exists(key)).isTrue();
    }

    @Test
    void keyNeverStoredDoesNotExist() {
        assertThat(storage.exists(key())).isFalse();
    }

    @Test
    void storingAgainReplacesTheObject() {
        String key = key();
        storage.put(key, new byte[] {1, 2, 3}, "image/png");

        storage.put(key, new byte[] {9}, "image/png");

        assertThat(storage.get(key)).containsExactly(9);
    }

    @Test
    void putUsesServerSideEncryption() {
        String key = key();

        storage.put(key, new byte[] {1}, "application/pdf");

        HeadObjectResponse head =
                s3.headObject(request -> request.bucket(BUCKET).key(key));
        assertThat(head.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(head.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void missingKeyThrowsStorageException() {
        String key = key();

        assertThatThrownBy(() -> storage.get(key))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(key)
                .hasMessageContaining("get");
    }

    @Test
    void missingBucketThrowsStorageException() {
        try (S3Client client = S3Config.s3Client(settings("no-such-bucket"))) {
            DocumentStorageClient noBucket = new S3DocumentStorageClient(client, "no-such-bucket", "AES256");
            String key = key();

            assertThatThrownBy(() -> noBucket.put(key, new byte[] {1}, "application/pdf"))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining(key)
                    .hasMessageContaining("put");
        }
    }

    /** Through the beans S3Config declares, with settings bound as the application binds them. */
    @Test
    void configuredBeansStoreInTheConfiguredBucketWithTheConfiguredEncryption() {
        new ApplicationContextRunner()
                .withUserConfiguration(S3Config.class, PropertiesConfig.class)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "app.document-reader.s3.bucket=" + BUCKET,
                        "app.document-reader.s3.region=" + LOCALSTACK.getRegion(),
                        "app.document-reader.s3.endpoint=" + LOCALSTACK.getEndpoint(),
                        "app.document-reader.s3.path-style-access=true",
                        "app.document-reader.s3.server-side-encryption=AES256")
                .run(context -> {
                    S3Keys keys = context.getBean(S3Keys.class);
                    String key = keys.page(UUID.randomUUID(), 1);
                    assertThat(key).startsWith("files/");

                    context.getBean(DocumentStorageClient.class).put(key, new byte[] {1}, "image/png");

                    HeadObjectResponse head =
                            s3.headObject(request -> request.bucket(BUCKET).key(key));
                    assertThat(head.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
                });
    }

    @EnableConfigurationProperties(DocumentReaderProperties.class)
    static class PropertiesConfig {}

    @Test
    void encryptionTheSdkDoesNotKnowIsRefused() {
        assertThatThrownBy(() -> new S3DocumentStorageClient(s3, BUCKET, "test-bucket"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullBytesAreRefusedClearly() {
        assertThatThrownBy(() -> storage.put(key(), null, "image/png"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bytes");
    }
}
