package com.bnaqica.documentreader.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Every processing setting of the service, bound from {@code app.document-reader.*}. The defaults live in
 * {@code application.yml} so one file shows every value; see the design's §17 for what each one controls.
 *
 * <p>Settings that can't work stop the service at startup. The {@code @AssertTrue} methods cover what Jakarta
 * constraints can't express (odd run counts, positive durations and sizes); each is named after its setting, so the
 * startup error names it too.
 */
@Validated
@ConfigurationProperties("app.document-reader")
public record DocumentReaderProperties(
        @Valid @NotNull Batch batch,
        @Valid @NotNull File file,
        @Valid @NotNull Conversion conversion,
        @Valid @NotNull Separation separation,
        @Valid @NotNull Classification classification,
        @Valid @NotNull Extraction extraction,
        @Valid @NotNull OpenAi openAi,
        // `wait()` would clash with Object.wait(), so the component has another name but binds to `wait`
        @Valid @NotNull @Name("wait") Wait waitSettings,
        @Valid @NotNull Outbox outbox,
        @Valid @NotNull Inbox inbox,
        @Valid @NotNull Webhook webhook,
        @Valid @NotNull S3 s3,
        @Valid @NotNull Workers workers) {

    /** Limits for one request. */
    public record Batch(@Positive int maxFiles, DataSize maxTotalSize) {

        @AssertTrue(message = "must be positive")
        public boolean isMaxTotalSizePositive() {
            return positive(maxTotalSize);
        }
    }

    /** Limits for one uploaded file. */
    public record File(DataSize maxSize, @Positive int maxPages) {

        @AssertTrue(message = "must be positive")
        public boolean isMaxSizePositive() {
            return positive(maxSize);
        }
    }

    /** How uploads are rendered into page images. */
    public record Conversion(@Positive int dpi, @Positive int maxDimension) {}

    /** How a file is split into documents. A window overlap of zero is allowed. */
    public record Separation(
            @Positive int runs,
            @Positive int windowSize,
            @PositiveOrZero int windowOverlap) {

        @AssertTrue(message = "must be odd, so every page gap has a majority")
        public boolean isRunsOdd() {
            return runs % 2 == 1;
        }
    }

    /**
     * How a document's type is decided. {@code minSuccessfulRuns} defaults to a majority of {@code runs}, so it is
     * never null once bound.
     */
    public record Classification(
            @Positive int runs,
            @Positive int tiebreakRuns,
            @Positive int maxPages,
            @Positive int parallelism,
            @Positive Integer minSuccessfulRuns) {

        public Classification {
            if (minSuccessfulRuns == null) {
                minSuccessfulRuns = runs / 2 + 1;
            }
        }

        @AssertTrue(message = "must be odd, so a two-way tie always has a winner")
        public boolean isTiebreakRunsOdd() {
            return tiebreakRuns % 2 == 1;
        }

        @AssertTrue(message = "must not be more than classification runs")
        public boolean isMinSuccessfulRunsWithinRuns() {
            return minSuccessfulRuns <= runs;
        }
    }

    /** How a document's data is extracted. */
    public record Extraction(@Positive int passes, @Positive int maxPages) {}

    /** Which OpenAI model is called, and how varied its answers are for each step. */
    public record OpenAi(
            String model,
            double separationTemperature,
            double classificationTemperature,
            double extractionTemperature) {}

    /**
     * How long a synchronous request waits for its batch. Its errors would otherwise be reported under the component
     * name {@code waitSettings}, which isn't a setting, so the messages name the real keys.
     */
    public record Wait(Duration timeout, Duration pollInterval) {

        @AssertTrue(message = "app.document-reader.wait.timeout must be positive")
        public boolean isTimeoutPositive() {
            return positive(timeout);
        }

        @AssertTrue(message = "app.document-reader.wait.poll-interval must be positive")
        public boolean isPollIntervalPositive() {
            return positive(pollInterval);
        }
    }

    /** How often the outbox is checked for messages to dispatch. */
    public record Outbox(Duration pollInterval) {

        @AssertTrue(message = "must be positive")
        public boolean isPollIntervalPositive() {
            return positive(pollInterval);
        }
    }

    /** Retries, backoff and leases for inbox messages; {@code concurrency} is how many a worker handles at once. */
    public record Inbox(
            @Positive int maxAttempts,
            Duration backoffInitial,
            Duration backoffMax,
            Duration lease,
            @Positive int concurrency) {

        @AssertTrue(message = "must be positive")
        public boolean isBackoffInitialPositive() {
            return positive(backoffInitial);
        }

        @AssertTrue(message = "must be positive")
        public boolean isBackoffMaxPositive() {
            return positive(backoffMax);
        }

        @AssertTrue(message = "must be positive")
        public boolean isLeasePositive() {
            return positive(lease);
        }
    }

    /** Retries for webhook delivery. */
    public record Webhook(@Positive int maxAttempts, Duration timeout) {

        @AssertTrue(message = "must be positive")
        public boolean isTimeoutPositive() {
            return positive(timeout);
        }
    }

    /** The background dispatcher and inbox workers. Tests turn them off so no loop races a test. */
    public record Workers(boolean enabled) {}

    /**
     * Where files are stored. {@code endpoint} is only set to reach a non-AWS endpoint such as LocalStack.
     * {@code serverSideEncryption} is {@code aws:kms} or {@code AES256}, as S3 writes it.
     */
    public record S3(
            @NotBlank String bucket,

            @NotBlank @Pattern(regexp = ".*/", message = "must end with /")
            String prefix,

            @NotBlank String region,
            URI endpoint,
            boolean pathStyleAccess,

            @NotNull @Pattern(regexp = "aws:kms|AES256", message = "must be aws:kms or AES256")
            String serverSideEncryption) {

        /**
         * Spring percent-encodes characters a URI can't hold, so check the result can actually be reached. A host
         * with an underscore (some Docker Compose service names) has no URI host and is refused: use another name.
         */
        @AssertTrue(message = "must be an http or https URL with a host")
        public boolean isEndpointReachable() {
            return endpoint == null
                    || (endpoint.getHost() != null
                            && ("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())));
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.isPositive();
    }

    private static boolean positive(DataSize size) {
        return size != null && size.toBytes() > 0;
    }
}
