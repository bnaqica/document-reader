package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The `local` profile reaches S3 through the shared local-docker LocalStack; other profiles use AWS. */
class LocalProfileTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfig.class);

    @EnableConfigurationProperties(DocumentReaderProperties.class)
    static class PropertiesConfig {}

    @Test
    void localProfileUsesLocalStack() {
        runner.withPropertyValues("spring.profiles.active=local").run(context -> {
            DocumentReaderProperties.S3 s3 =
                    context.getBean(DocumentReaderProperties.class).s3();

            assertThat(s3.endpoint()).isEqualTo("http://localhost:4566");
            assertThat(s3.pathStyleAccess()).isTrue();
            assertThat(s3.bucket()).isEqualTo("document-reader-local");
        });
    }

    @Test
    void defaultProfileHasNoEndpointOverride() {
        runner.run(context -> {
            DocumentReaderProperties.S3 s3 =
                    context.getBean(DocumentReaderProperties.class).s3();

            assertThat(s3.endpoint()).isNull();
            assertThat(s3.pathStyleAccess()).isFalse();
        });
    }
}
