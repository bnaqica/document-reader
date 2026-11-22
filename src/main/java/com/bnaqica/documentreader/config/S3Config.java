package com.bnaqica.documentreader.config;

import com.bnaqica.documentreader.client.DocumentStorageClient;
import com.bnaqica.documentreader.processors.S3Keys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/** The S3 client, the storage contract over it, and the key layout. */
@Configuration
public class S3Config {

    /** LocalStack accepts any credentials, so an endpoint override needs no AWS variables. */
    private static final String PLACEHOLDER_CREDENTIAL = "test";

    @Bean(destroyMethod = "close")
    S3Client s3Client(DocumentReaderProperties properties) {
        return s3Client(properties.s3());
    }

    @Bean
    DocumentStorageClient documentStorageClient(S3Client s3Client, DocumentReaderProperties properties) {
        return new S3DocumentStorageClient(
                s3Client, properties.s3().bucket(), properties.s3().serverSideEncryption());
    }

    @Bean
    S3Keys s3Keys(DocumentReaderProperties properties) {
        return new S3Keys(properties.s3().prefix());
    }

    /**
     * With an endpoint override (LocalStack), fixed placeholder credentials; otherwise the AWS default chain, which
     * resolves lazily, so a context that never calls S3 starts without credentials.
     */
    static S3Client s3Client(DocumentReaderProperties.S3 s3) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3.region()))
                .forcePathStyle(s3.pathStyleAccess())
                .httpClientBuilder(ApacheHttpClient.builder());
        if (s3.endpoint() != null) {
            builder.endpointOverride(s3.endpoint())
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(PLACEHOLDER_CREDENTIAL, PLACEHOLDER_CREDENTIAL)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }
}
