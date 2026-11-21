package com.bnaqica.documentreader.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CallerTests {

    private static Jwt jwt(String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").claim("scope", "x");
        return subject == null ? builder.build() : builder.subject(subject).build();
    }

    @Test
    void jwtCallerIsItsSubject() {
        Caller caller = Caller.of(new JwtAuthenticationToken(jwt("user-1"), List.of()));

        assertThat(caller).isEqualTo(new Caller("user-1", false));
        assertThat(caller.mayRead("user-1")).isTrue();
        assertThat(caller.mayRead("user-2")).isFalse();
    }

    @Test
    void adminRoleMakesAnAdminCaller() {
        Caller caller = Caller.of(
                new JwtAuthenticationToken(jwt("admin-1"), List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThat(caller.admin()).isTrue();
        assertThat(caller.mayRead("user-2")).isTrue();
    }

    /** The local profile: anonymous, but with the admin role. */
    @Test
    void localAnonymousAdminIsAnAdmin() {
        Caller caller = Caller.of(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS", "ROLE_ADMIN")));

        assertThat(caller.admin()).isTrue();
        assertThat(caller.subject()).isEqualTo("anonymousUser");
    }

    @Test
    void tokenWithoutSubjectReadsNoOnesBatch() {
        Caller caller = Caller.of(new JwtAuthenticationToken(jwt(null), List.of()));

        assertThat(caller.mayRead("user-1")).isFalse();
        assertThat(caller.mayRead(null)).isFalse();
    }
}
