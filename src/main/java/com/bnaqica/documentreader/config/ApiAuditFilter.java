package com.bnaqica.documentreader.config;

import com.bnaqica.documentreader.dto.ApiAuditEntry;
import com.bnaqica.documentreader.processors.Sha256;
import com.bnaqica.documentreader.service.ApiAuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records every POST, PUT and DELETE in {@code audit.api_audit}, whether it succeeded or not.
 *
 * <p>{@link ApiAuditFilterConfig} runs this ahead of the Spring Security filter chain, so requests rejected by
 * security are audited too. That is also why the token is read here: the security context has been cleared by
 * the time the chain returns.
 */
@Slf4j
@RequiredArgsConstructor
public class ApiAuditFilter extends OncePerRequestFilter {

    private static final Set<String> AUDITED_METHODS = Set.of("POST", "PUT", "DELETE");
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_PATH_LENGTH = 2048; // api_audit.api_path
    private static final String ANONYMOUS = "anonymous";
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final String MULTIPART = "multipart/";
    private static final int MAX_FIELD_BYTES = 2048; // the longest a webhook URL is expected to be
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApiAuditService auditService;
    private final JwtDecoder jwtDecoder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AUDITED_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Read before the chain: once the request is handled, Spring deletes the uploaded parts' files
        String multipart = isMultipart(request) ? multipartPayload(request) : null;
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, MAX_PAYLOAD_BYTES);
        boolean threw = true;
        try {
            chain.doFilter(wrapped, response);
            threw = false;
        } finally {
            audit(wrapped, response, threw, multipart);
        }
    }

    private void audit(
            ContentCachingRequestWrapper request, HttpServletResponse response, boolean threw, String multipart) {
        try {
            int status = threw ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
            auditService.record(ApiAuditEntry.builder()
                    .apiPath(truncate(request.getRequestURI()))
                    .action(request.getMethod())
                    .requestPayload(isMultipart(request) ? multipart : payload(request))
                    .performedBy(performedBy(request))
                    .responseStatus(status)
                    .success(status < HttpServletResponse.SC_BAD_REQUEST)
                    .build());
        } catch (RuntimeException | IOException ex) {
            // The request has already been handled; a failed audit write must not change its outcome
            log.error("Failed to audit {} {}", request.getMethod(), request.getRequestURI(), ex);
        }
    }

    private String payload(ContentCachingRequestWrapper request) throws IOException {
        if (request.getContentAsByteArray().length == 0) {
            // Security rejected the request before anything read the body
            request.getInputStream().readAllBytes();
        }
        return new String(request.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.regionMatches(true, 0, MULTIPART, 0, MULTIPART.length());
    }

    /**
     * The form fields and each file's name, declared type, size and SHA-256, never the bytes (§16). Tomcat parses the
     * parts once and keeps them (or the parse failure) for the request, so the handler sees the same parts. The raw
     * body is never read. Null when the upload can't be parsed, for example because it is over the upload limits; the
     * handler then answers that request with its own error.
     */
    private static String multipartPayload(HttpServletRequest request) {
        Collection<Part> parts;
        try {
            parts = request.getParts();
        } catch (IllegalStateException | ServletException | IOException e) {
            return null;
        }
        try {
            return describe(parts);
        } catch (IOException | RuntimeException e) {
            // A failed audit payload must not change the request's outcome
            log.error("Failed to describe the parts of {} {}", request.getMethod(), request.getRequestURI(), e);
            return null;
        }
    }

    private static String describe(Collection<Part> parts) throws IOException {
        ObjectNode payload = JSON.createObjectNode();
        ArrayNode files = JSON.createArrayNode();
        for (Part part : parts) {
            if (part.getSubmittedFileName() == null) {
                try (InputStream value = part.getInputStream()) {
                    // A field can be as large as a file part, so only its start is recorded
                    payload.put(part.getName(), new String(value.readNBytes(MAX_FIELD_BYTES), StandardCharsets.UTF_8));
                }
            } else {
                String sha256;
                try (InputStream content = part.getInputStream()) {
                    sha256 = Sha256.hex(content);
                }
                files.addObject()
                        .put("name", part.getSubmittedFileName())
                        .put("contentType", part.getContentType())
                        .put("size", part.getSize())
                        .put("sha256", sha256);
            }
        }
        payload.set("files", files);
        return JSON.writeValueAsString(payload);
    }

    private String performedBy(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return ANONYMOUS;
        }
        try {
            Jwt jwt = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()));
            String username = jwt.getClaimAsString("preferred_username");
            if (username != null) {
                return username;
            }
            return jwt.getSubject() != null ? jwt.getSubject() : ANONYMOUS;
        } catch (RuntimeException ex) {
            return ANONYMOUS; // invalid or expired token, or the issuer can't be reached
        }
    }

    private static String truncate(String path) {
        return path.length() <= MAX_PATH_LENGTH ? path : path.substring(0, MAX_PATH_LENGTH);
    }
}
