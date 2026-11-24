package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.client.DocumentStorageClient;
import com.bnaqica.documentreader.processors.ConversionFixtures;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends real multipart requests to a running server, because MockMvc skips Tomcat's multipart parsing, which is
 * what the audit filter reads the parts from. Batches are async, so no request waits for its result.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.document-reader.file.max-size=1MB")
@Import(TestcontainersConfiguration.class)
class ApiAuditFilterTests {

    private static final String BOUNDARY = "audit-boundary";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private DocumentStorageClient storage;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String subject = "audit-" + UUID.randomUUID();

    @BeforeEach
    void acceptAnyToken() {
        given(jwtDecoder.decode(anyString()))
                .willReturn(Jwt.withTokenValue("token")
                        .header("alg", "none")
                        .subject(subject)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build());
    }

    @AfterEach
    void cleanUp() {
        for (UUID batch : jdbc.sql("SELECT id FROM document_reader.batch WHERE requested_by = ?")
                .param(subject)
                .query(UUID.class)
                .list()) {
            jdbc.sql("""
                            DELETE FROM document_reader.outbox_message WHERE aggregate_id = :batch
                               OR aggregate_id IN (SELECT id FROM document_reader.source_file WHERE batch_id = :batch)
                            """).param("batch", batch).update();
            new ProcessingFixtures(jdbc).deleteBatch(batch);
        }
        jdbc.sql("DELETE FROM audit.api_audit WHERE performed_by = ?")
                .param(subject)
                .update();
    }

    @Test
    void auditPayloadExcludesFileBytes() throws Exception {
        byte[] pdf = ConversionFixtures.pdf(1);
        byte[] png = ConversionFixtures.png(40, 30);

        HttpResponse<String> response = send(new Multipart()
                .field("loanPurpose", "CAR_REFINANCE")
                .field("async", "true")
                .field("webhookUrl", "https://example.com/hook")
                .file("packet.pdf", "application/pdf", pdf)
                .file("license.png", "image/png", png));

        assertThat(response.statusCode()).isEqualTo(202);
        Map<String, Object> row = onlyAuditRow();
        assertThat(row).containsEntry("response_status", 202).containsEntry("success", true);
        String payload = (String) row.get("request_payload");
        JsonNode json = JsonMapper.builder().build().readTree(payload);
        assertThat(json.get("loanPurpose").asString()).isEqualTo("CAR_REFINANCE");
        assertThat(json.get("async").asString()).isEqualTo("true");
        assertThat(json.get("webhookUrl").asString()).isEqualTo("https://example.com/hook");
        assertThat(json.get("files")).hasSize(2);
        assertFile(json.get("files").get(0), "packet.pdf", "application/pdf", pdf);
        assertFile(json.get("files").get(1), "license.png", "image/png", png);
        // No file content in any form: the PDF header would show up as text
        assertThat(payload).doesNotContain("%PDF").hasSizeLessThan(1000);
    }

    @Test
    void refusedUploadKeepsMetadataPayload() throws Exception {
        HttpResponse<String> response = send(new Multipart()
                .field("loanPurpose", "CAR_REFINANCE")
                .file("notes.docx", "application/octet-stream", ConversionFixtures.docx()));

        assertThat(response.statusCode()).isEqualTo(415);
        Map<String, Object> row = onlyAuditRow();
        assertThat(row).containsEntry("response_status", 415).containsEntry("success", false);
        JsonNode json = JsonMapper.builder().build().readTree((String) row.get("request_payload"));
        assertThat(json.get("files").get(0).get("name").asString()).isEqualTo("notes.docx");
    }

    @Test
    void longFieldValueIsCappedInThePayload() throws Exception {
        String longValue = "A".repeat(900_000);

        send(new Multipart()
                .field("loanPurpose", longValue)
                .file("a.pdf", "application/pdf", ConversionFixtures.pdf(1)));

        String payload = (String) onlyAuditRow().get("request_payload");
        assertThat(payload.length()).isLessThan(10_000);
        assertThat(JsonMapper.builder().build().readTree(payload).get("files")).hasSize(1);
    }

    @Test
    void oversizedUploadIsAuditedAs413() throws Exception {
        HttpResponse<String> response = send(new Multipart()
                .field("loanPurpose", "CAR_REFINANCE")
                .file("big.pdf", "application/pdf", new byte[1536 * 1024]));

        assertThat(response.statusCode()).isEqualTo(413);
        Map<String, Object> row = onlyAuditRow();
        assertThat(row)
                .containsEntry("api_path", "/batches")
                .containsEntry("action", "POST")
                .containsEntry("response_status", 413)
                .containsEntry("success", false);
        assertThat(row.get("request_payload")).isNull();
    }

    private static void assertFile(JsonNode file, String name, String contentType, byte[] bytes) throws Exception {
        assertThat(file.get("name").asString()).isEqualTo(name);
        assertThat(file.get("contentType").asString()).isEqualTo(contentType);
        assertThat(file.get("size").asLong()).isEqualTo(bytes.length);
        assertThat(file.get("sha256").asString())
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private Map<String, Object> onlyAuditRow() {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT api_path, action, request_payload, response_status, success
                        FROM audit.api_audit WHERE performed_by = ?
                        """).param(subject).query().listOfRows();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private HttpResponse<String> send(Multipart body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/batches"))
                .header("Authorization", "Bearer token")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A hand-built multipart body, so the test controls every part exactly. */
    private static final class Multipart {

        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        Multipart field(String name, String value) {
            ascii("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value
                    + "\r\n");
            return this;
        }

        Multipart file(String fileName, String contentType, byte[] bytes) {
            ascii("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + fileName
                    + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
            body.writeBytes(bytes);
            ascii("\r\n");
            return this;
        }

        byte[] bytes() {
            ascii("--" + BOUNDARY + "--\r\n");
            return body.toByteArray();
        }

        private void ascii(String text) {
            body.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
