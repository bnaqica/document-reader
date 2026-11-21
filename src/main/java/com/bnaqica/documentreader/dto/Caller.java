package com.bnaqica.documentreader.dto;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is asking, as record reads need it: the token's subject, and whether they hold the admin role. Controllers get
 * the authentication with {@code @CurrentSecurityContext}, because a plain {@code Authentication} parameter is null
 * for the {@code local} profile's anonymous admin.
 */
public record Caller(String subject, boolean admin) {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    public static Caller of(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> ADMIN_ROLE.equals(authority.getAuthority()));
        String subject = authentication instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getSubject()
                : authentication.getName();
        return new Caller(subject, admin);
    }

    /** Whether this caller may read a batch sent by {@code requestedBy}. A caller with no subject owns nothing. */
    public boolean mayRead(String requestedBy) {
        return admin || (subject != null && subject.equals(requestedBy));
    }
}
