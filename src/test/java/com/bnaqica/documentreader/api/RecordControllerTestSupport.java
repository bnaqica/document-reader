package com.bnaqica.documentreader.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** Callers the record controller tests send requests as. */
final class RecordControllerTestSupport {

    private RecordControllerTestSupport() {}

    static JwtRequestPostProcessor user1() {
        return jwt().jwt(token -> token.subject("user-1"));
    }

    static JwtRequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
