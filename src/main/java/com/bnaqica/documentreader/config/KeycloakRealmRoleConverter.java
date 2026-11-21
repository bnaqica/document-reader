package com.bnaqica.documentreader.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns Keycloak realm roles ({@code realm_access.roles}) into {@code ROLE_} authorities in upper case, so the realm
 * role {@code admin} satisfies {@code hasRole('ADMIN')}. Spring's default reads only scopes, which this service
 * doesn't use.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(
                        "ROLE_" + String.valueOf(role).toUpperCase(Locale.ROOT)))
                .toList();
    }
}
