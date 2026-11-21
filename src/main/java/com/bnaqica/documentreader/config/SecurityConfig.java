package com.bnaqica.documentreader.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every endpoint needs a valid JWT from {@code JWT_ISSUER_URI}, except the health check. CSRF protection is off
 * because requests carry a bearer token and there are no sessions or cookies.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @Profile("!local")
    SecurityFilterChain securedFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.requestMatchers("/actuator/health/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(realmRoles())))
                .build();
    }

    /** Roles come from Keycloak's {@code realm_access.roles}, so the realm role {@code admin} grants ADMIN. */
    private static JwtAuthenticationConverter realmRoles() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /**
     * No authentication in the {@code local} profile, so the API and Swagger UI work without an identity provider.
     * Every request acts as an admin, so reference data can be maintained on a developer machine.
     */
    @Bean
    @Profile("local")
    SecurityFilterChain localFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .anonymous(anonymous -> anonymous.authorities("ROLE_ANONYMOUS", "ROLE_ADMIN"))
                .build();
    }
}
