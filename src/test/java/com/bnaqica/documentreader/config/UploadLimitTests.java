package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Sends real multipart requests to a running server, because MockMvc skips multipart parsing and so never applies
 * the size limit. The endpoint exists only in this test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.document-reader.file.max-size=10MB")
@Import({TestcontainersConfiguration.class, UploadLimitTests.UploadController.class})
class UploadLimitTests {

    private static final String BOUNDARY = "upload-limit-boundary";

    @RestController
    static class UploadController {

        @PostMapping("/test-upload")
        long upload(@RequestPart("file") MultipartFile file) {
            return file.getSize();
        }
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void acceptAnyToken() {
        given(jwtDecoder.decode(anyString()))
                .willReturn(Jwt.withTokenValue("token")
                        .header("alg", "none")
                        .subject(UUID.randomUUID().toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build());
    }

    @Test
    void fileOverLimitIsRefused() throws Exception {
        int tenAndAHalfMegabytes = 10 * 1024 * 1024 + 512 * 1024;

        HttpResponse<String> response = upload(tenAndAHalfMegabytes);

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void fileWithinLimitIsAccepted() throws Exception {
        int oneMegabyte = 1024 * 1024;

        HttpResponse<String> response = upload(oneMegabyte);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(String.valueOf(oneMegabyte));
    }

    private HttpResponse<String> upload(int fileSize) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test-upload"))
                .header("Authorization", "Bearer token")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(fileSize)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipartBody(int fileSize) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"scan.pdf\"\r\n"
                        + "Content-Type: application/pdf\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(new byte[fileSize]);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }
}
