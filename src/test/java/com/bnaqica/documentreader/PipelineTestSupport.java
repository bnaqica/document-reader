package com.bnaqica.documentreader;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole application with its workers on, against one Postgres and one LocalStack that every instance shares, so a
 * second instance started with {@link #startInstance} works on the same batches. Requests go over real HTTP as
 * multipart, as a caller sends them.
 */
abstract class PipelineTestSupport {

    static final Duration DEADLINE = Duration.ofSeconds(60);
    private static final String BUCKET = "pipeline-test";
    private static final String BOUNDARY = "pipeline-boundary";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4"));

    static {
        // started once for the JVM and shared by every instance; Testcontainers' reaper stops them at exit
        POSTGRES.start();
        LOCALSTACK.start();
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            s3.createBucket(request -> request.bucket(BUCKET));
        }
    }

    /**
     * Every instance's settings: the shared stores, workers on, and short polls so the tests run quickly. Leases and
     * retries keep the application's defaults: a lease short enough to run out during a slow step would let another
     * instance handle the same message twice.
     */
    static Map<String, Object> sharedProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("app.document-reader.s3.bucket", BUCKET);
        properties.put("app.document-reader.s3.region", LOCALSTACK.getRegion());
        properties.put(
                "app.document-reader.s3.endpoint", LOCALSTACK.getEndpoint().toString());
        properties.put("app.document-reader.s3.path-style-access", "true");
        properties.put("app.document-reader.s3.server-side-encryption", "AES256");
        properties.put("app.document-reader.workers.enabled", "true");
        properties.put("app.document-reader.outbox.poll-interval", "200ms");
        properties.put("app.document-reader.wait.timeout", "60s");
        properties.put("app.document-reader.wait.poll-interval", "200ms");
        return properties;
    }

    @DynamicPropertySource
    static void shareStores(DynamicPropertyRegistry registry) {
        sharedProperties().forEach((key, value) -> registry.add(key, () -> value));
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void acceptAnyToken() {
        given(jwtDecoder.decode(anyString()))
                .willReturn(Jwt.withTokenValue("token")
                        .header("alg", "none")
                        .subject(UUID.randomUUID().toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build());
    }

    /**
     * Another instance on the same database and bucket, with its own worker id, web server and the given extra
     * configuration (its {@code ChatModel}). Close it when done.
     */
    static ConfigurableApplicationContext startInstance(Class<?> configuration) {
        return startInstance(configuration, Map.of());
    }

    /** As {@link #startInstance(Class)}, with settings of its own on top of the shared ones. */
    static ConfigurableApplicationContext startInstance(Class<?> configuration, Map<String, String> extra) {
        Map<String, Object> properties = sharedProperties();
        properties.put("server.port", "0");
        properties.putAll(extra);
        // as arguments, which outrank application.yml; builder properties are only defaults
        String[] arguments = properties.entrySet().stream()
                .map(property -> "--" + property.getKey() + "=" + property.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(DocumentReaderApplication.class, configuration)
                .web(WebApplicationType.SERVLET)
                // as @SpringBootTest does, keep the test sources' own configurations and controllers out of the scan
                .initializers(context ->
                        context.getBeanFactory().registerSingleton("excludeTestClasses", new ExcludeTestClasses()))
                .run(arguments);
    }

    /** The packet and the licence as one {@code CAR_REFINANCE} batch. */
    HttpResponse<String> submit(boolean async, String webhookUrl) throws IOException, InterruptedException {
        return submit(
                async,
                webhookUrl,
                List.of(
                        Map.entry(Fixtures.PACKET, Fixtures.packet()),
                        Map.entry(Fixtures.LICENSE, Fixtures.license())));
    }

    /** The files in the order given, so the first is file 0. */
    HttpResponse<String> submit(boolean async, String webhookUrl, List<Map.Entry<String, byte[]>> files)
            throws IOException, InterruptedException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, "loanPurpose", "CAR_REFINANCE");
        field(body, "async", Boolean.toString(async));
        if (webhookUrl != null) {
            field(body, "webhookUrl", webhookUrl);
        }
        files.forEach(file -> {
            String type = file.getKey().endsWith(".pdf") ? "application/pdf" : "image/jpeg";
            ascii(
                    body,
                    "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + file.getKey()
                            + "\"\r\nContent-Type: " + type + "\r\n\r\n");
            body.writeBytes(file.getValue());
            ascii(body, "\r\n");
        });
        ascii(body, "--" + BOUNDARY + "--\r\n");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/batches"))
                .header("Authorization", "Bearer token")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .timeout(DEADLINE.plusSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    JsonNode getBatch(UUID batchId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/batches/" + batchId))
                .header("Authorization", "Bearer token")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET /batches/" + batchId + " answered " + response.statusCode());
        }
        return jsonMapper.readTree(response.body());
    }

    /** The batch's status once it is final, polling the database until {@link #DEADLINE}. */
    String awaitFinal(UUID batchId) throws InterruptedException {
        return awaitFinal(batchId, DEADLINE);
    }

    String awaitFinal(UUID batchId, Duration within) throws InterruptedException {
        Instant deadline = Instant.now().plus(within);
        while (Instant.now().isBefore(deadline)) {
            String status =
                    jdbc.queryForObject("SELECT status FROM document_reader.batch WHERE id = ?", String.class, batchId);
            if (status.equals("COMPLETED") || status.equals("PARTIALLY_COMPLETED") || status.equals("FAILED")) {
                return status;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Batch " + batchId + " wasn't final within " + within);
    }

    private static void field(ByteArrayOutputStream body, String name, String value) {
        ascii(
                body,
                "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void ascii(ByteArrayOutputStream body, String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Leaves out every class compiled from the test sources, wherever the build tool writes them. */
    static final class ExcludeTestClasses extends TypeExcludeFilter {

        private static final String TEST_CLASSES = PipelineTestSupport.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toString();

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            return reader.getResource().getURL().toString().startsWith(TEST_CLASSES);
        }
    }
}
