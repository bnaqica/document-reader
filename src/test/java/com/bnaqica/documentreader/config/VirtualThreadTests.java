package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Requests must run on virtual threads, so a call waiting for its batch doesn't hold a platform thread. This needs a
 * real server: MockMvc runs requests on the test's own thread.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, VirtualThreadTests.ThreadController.class})
class VirtualThreadTests {

    @RestController
    static class ThreadController {

        @GetMapping("/test-thread")
        boolean isVirtual() {
            return Thread.currentThread().isVirtual();
        }
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void requestRunsOnVirtualThread() throws Exception {
        given(jwtDecoder.decode(anyString()))
                .willReturn(Jwt.withTokenValue("token")
                        .header("alg", "none")
                        .subject("caller")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test-thread"))
                .header("Authorization", "Bearer token")
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("true");
    }
}
