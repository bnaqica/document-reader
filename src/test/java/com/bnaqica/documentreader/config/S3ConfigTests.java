package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

class S3ConfigTests {

    private static DocumentReaderProperties.S3 s3(URI endpoint) {
        return new DocumentReaderProperties.S3("bucket", "files/", "us-east-1", endpoint, endpoint != null, "AES256");
    }

    @Test
    void endpointOverrideUsesPlaceholderCredentials() {
        try (S3Client client = S3Config.s3Client(s3(URI.create("http://localhost:4566")))) {
            AwsCredentials credentials = (AwsCredentials) client.serviceClientConfiguration()
                    .credentialsProvider()
                    .resolveIdentity()
                    .join();

            assertThat(credentials.accessKeyId()).isEqualTo("test");
            assertThat(credentials.secretAccessKey()).isEqualTo("test");
        }
    }

    @Test
    void awsUsesTheDefaultCredentialChain() {
        try (S3Client client = S3Config.s3Client(s3(null))) {
            assertThat(client.serviceClientConfiguration().credentialsProvider())
                    .isInstanceOf(DefaultCredentialsProvider.class);
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void endpointOverrideIsUsed() {
        try (S3Client client = S3Config.s3Client(s3(URI.create("http://localhost:4566")))) {
            assertThat(client.serviceClientConfiguration().endpointOverride())
                    .contains(URI.create("http://localhost:4566"));
        }
    }

    /** Only the synchronous Apache client is shipped; the SDK's Netty client is excluded from the build. */
    @Test
    void nettyClientIsNotOnTheClasspath() {
        assertThatThrownBy(() -> Class.forName("software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(classExists("software.amazon.awssdk.http.apache.ApacheHttpClient"))
                .isTrue();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
