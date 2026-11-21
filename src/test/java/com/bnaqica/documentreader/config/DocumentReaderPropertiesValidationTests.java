package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Settings that can't work must stop the service at startup, with a message naming the setting. */
class DocumentReaderPropertiesValidationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfig.class);

    @EnableConfigurationProperties(DocumentReaderProperties.class)
    static class PropertiesConfig {}

    /** Each failing setting is named by the key an operator writes, not by a Java field or check name. */
    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
        "batch.max-files, 0",
        "batch.max-total-size, 0MB",
        "file.max-size, 0MB",
        "file.max-pages, 0",
        "conversion.dpi, 0",
        "conversion.max-dimension, 0",
        "separation.runs, 4",
        "separation.window-size, 0",
        "classification.runs, 0",
        "classification.tiebreak-runs, 4",
        "classification.max-pages, 0",
        "classification.parallelism, 0",
        "classification.min-successful-runs, 6",
        "extraction.passes, 0",
        "extraction.max-pages, 0",
        "wait.timeout, 0s",
        "wait.poll-interval, 0s",
        "outbox.poll-interval, 0s",
        "inbox.max-attempts, 0",
        "inbox.backoff-initial, 0s",
        "inbox.backoff-max, 0s",
        "inbox.lease, 0s",
        "inbox.concurrency, 0",
        "webhook.max-attempts, 0",
        "webhook.timeout, 0s"
    })
    void startupErrorNamesTheSettingKey(String setting, String value) {
        String key = "app.document-reader." + setting;
        runner.withPropertyValues(key + "=" + value).run(context -> assertStartupFailsNaming(context, key));
    }

    @Test
    void acceptsOddRunCounts() {
        runner.withPropertyValues(
                        "app.document-reader.separation.runs=5", "app.document-reader.classification.tiebreak-runs=3")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The startup error must name the setting as an operator writes it: either the dashed key
     * ({@code app.document-reader.wait.poll-interval}) or its camelCase form ({@code ...wait.pollInterval}), which
     * Spring also accepts as that key. An internal name such as {@code waitSettings.timeoutPositive} doesn't count.
     */
    private static void assertStartupFailsNaming(AssertableApplicationContext context, String key) {
        assertThat(context).hasFailed();
        assertThat(messagesOf(context.getStartupFailure())).containsAnyOf(key, camelCase(key));
    }

    private static String camelCase(String key) {
        StringBuilder camel = new StringBuilder();
        boolean upperNext = false;
        for (char c : key.toCharArray()) {
            if (c == '-') {
                upperNext = true;
            } else {
                camel.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            }
        }
        return camel.toString().replace("app.documentReader.", "app.document-reader.");
    }

    /** The binder reports the failing property deep in the cause chain, so search every message in it. */
    private static String messagesOf(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return String.join("\n", messages);
    }
}
