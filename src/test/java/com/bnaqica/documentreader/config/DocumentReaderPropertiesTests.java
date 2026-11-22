package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

/**
 * Binds {@link DocumentReaderProperties} from the real {@code application.yml} (plus the test overlay), so these
 * assertions check the defaults a deployed service starts with. Expected values come from the design's §17.
 */
class DocumentReaderPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfig.class);

    @EnableConfigurationProperties({DocumentReaderProperties.class, MultipartProperties.class})
    static class PropertiesConfig {}

    @Test
    void bindsDefaults() {
        runner.run(context -> {
            DocumentReaderProperties properties = context.getBean(DocumentReaderProperties.class);

            assertThat(properties.batch().maxFiles()).isEqualTo(20);
            assertThat(properties.batch().maxTotalSize()).isEqualTo(DataSize.ofMegabytes(200));
            assertThat(properties.file().maxSize()).isEqualTo(DataSize.ofMegabytes(50));
            assertThat(properties.file().maxPages()).isEqualTo(100);

            assertThat(properties.conversion().dpi()).isEqualTo(200);
            assertThat(properties.conversion().maxDimension()).isEqualTo(2048);

            assertThat(properties.separation().runs()).isEqualTo(3);
            assertThat(properties.separation().windowSize()).isEqualTo(15);
            assertThat(properties.separation().windowOverlap()).isEqualTo(2);

            assertThat(properties.classification().runs()).isEqualTo(5);
            assertThat(properties.classification().tiebreakRuns()).isEqualTo(3);
            assertThat(properties.classification().maxPages()).isEqualTo(3);
            assertThat(properties.classification().parallelism()).isEqualTo(5);

            assertThat(properties.extraction().passes()).isEqualTo(3);
            assertThat(properties.extraction().maxPages()).isEqualTo(20);

            assertThat(properties.openAi().separationTemperature()).isEqualTo(0.5);
            assertThat(properties.openAi().classificationTemperature()).isEqualTo(0.7);
            assertThat(properties.openAi().extractionTemperature()).isEqualTo(0.2);

            assertThat(properties.waitSettings().timeout()).isEqualTo(Duration.ofSeconds(300));
            assertThat(properties.waitSettings().pollInterval()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.outbox().pollInterval()).isEqualTo(Duration.ofSeconds(1));

            assertThat(properties.inbox().maxAttempts()).isEqualTo(5);
            assertThat(properties.inbox().backoffInitial()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.inbox().backoffMax()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.inbox().lease()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.inbox().concurrency()).isEqualTo(4);
            // the test overlay turns the background workers off; workersRunByDefault() checks the real default
            assertThat(properties.workers().enabled()).isFalse();

            assertThat(properties.webhook().maxAttempts()).isEqualTo(8);
            assertThat(properties.webhook().timeout()).isEqualTo(Duration.ofSeconds(10));

            assertThat(properties.s3().prefix()).isEqualTo("files/");
            assertThat(properties.s3().region()).isEqualTo("us-east-1");
            assertThat(properties.s3().endpoint()).isNull();
            assertThat(properties.s3().pathStyleAccess()).isFalse();
            assertThat(properties.s3().serverSideEncryption()).isEqualTo("aws:kms");
        });
    }

    @Test
    void overriddenLimitKeepsOtherDefaults() {
        runner.withPropertyValues("app.document-reader.batch.max-files=5").run(context -> {
            DocumentReaderProperties properties = context.getBean(DocumentReaderProperties.class);

            assertThat(properties.batch().maxFiles()).isEqualTo(5);
            assertThat(properties.batch().maxTotalSize()).isEqualTo(DataSize.ofMegabytes(200));
            assertThat(properties.file().maxSize()).isEqualTo(DataSize.ofMegabytes(50));
        });
    }

    @Test
    void minSuccessfulRunsDefaultsToMajority() {
        runner.run(context -> assertThat(context.getBean(DocumentReaderProperties.class)
                        .classification()
                        .minSuccessfulRuns())
                .isEqualTo(3));

        runner.withPropertyValues("app.document-reader.classification.runs=7")
                .run(context -> assertThat(context.getBean(DocumentReaderProperties.class)
                                .classification()
                                .minSuccessfulRuns())
                        .isEqualTo(4));
    }

    @Test
    void explicitMinSuccessfulRunsIsKept() {
        runner.withPropertyValues("app.document-reader.classification.min-successful-runs=5")
                .run(context -> assertThat(context.getBean(DocumentReaderProperties.class)
                                .classification()
                                .minSuccessfulRuns())
                        .isEqualTo(5));
    }

    @Test
    void multipartLimitsFollowSettings() {
        runner.run(context -> {
            MultipartProperties multipart = context.getBean(MultipartProperties.class);

            assertThat(multipart.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(50));
            assertThat(multipart.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(200));
        });
    }

    @Test
    void multipartLimitFollowsChangedFileLimit() {
        runner.withPropertyValues("app.document-reader.file.max-size=10MB")
                .run(context -> assertThat(
                                context.getBean(MultipartProperties.class).getMaxFileSize())
                        .isEqualTo(DataSize.ofMegabytes(10)));
    }

    @Test
    void readsCredentialsAndLocationsFromEnvironment() {
        runner.withPropertyValues(
                        "OPENAI_API_KEY=key-from-env", "OPENAI_MODEL=model-from-env", "S3_BUCKET=bucket-from-env")
                .run(context -> {
                    DocumentReaderProperties properties = context.getBean(DocumentReaderProperties.class);

                    assertThat(context.getEnvironment().getProperty("spring.ai.openai.api-key"))
                            .isEqualTo("key-from-env");
                    assertThat(properties.openAi().model()).isEqualTo("model-from-env");
                    assertThat(properties.s3().bucket()).isEqualTo("bucket-from-env");
                });
    }

    @Test
    void modelDefaultsWhenNotSetInEnvironment() {
        runner.run(context -> assertThat(
                        context.getBean(DocumentReaderProperties.class).openAi().model())
                .isEqualTo("gpt-4o"));
    }

    /** Read from the main application.yml alone, because the test overlay turns the workers off. */
    @Test
    void workersRunByDefault() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        assertThat(yaml.getObject()).containsEntry("app.document-reader.workers.enabled", true);
    }
}
