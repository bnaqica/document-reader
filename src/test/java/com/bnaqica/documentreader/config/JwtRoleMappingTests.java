package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Keycloak puts realm roles in {@code realm_access.roles}; each becomes a ROLE_ authority in upper case. */
class JwtRoleMappingTests {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("caller")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void realmRolesBecomeUpperCaseRoleAuthorities() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of("admin", "reader")))
                .build();

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_READER");
    }

    @Test
    void tokenWithoutRealmAccessHasNoAuthorities() {
        assertThat(converter.convert(token().build())).isEmpty();
    }

    @Test
    void realmAccessWithoutRolesHasNoAuthorities() {
        assertThat(converter.convert(token().claim("realm_access", Map.of()).build()))
                .isEmpty();
    }
}
