package com.ecommerce.api_gateway.security;

import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * The gateway's authentication and authorisation chain (ADR-0012).
 *
 * <p>Callers are authenticated by a Keycloak RS256 access token, verified locally against
 * the realm's cached JWKS. The gateway holds no signing key, so it cannot mint a token
 * (SEC-04), and an unverifiable one is refused with a 401 rather than escaping the check
 * as a 500 (BUG-19). There is no cookie and no second way in: the {@code springBootEcom}
 * cookie, {@code AuthenticationFilter} and the gateway's hand-written {@code JwtService}
 * are gone.</p>
 *
 * <p>The rules are built at startup from {@link GatewaySecurityProperties}, so
 * {@code gateway.security.public-paths} and {@code gateway.security.role-mappings} in
 * application.yaml remain the one place the policy is written down. Order matters and
 * mirrors the filter this replaces: pre-flight first, then public paths, then the
 * role-guarded prefixes, then "authenticated" for everything else.</p>
 *
 * <p>One semantic difference worth naming: where {@code AuthenticationFilter} unioned the
 * roles of <em>every</em> matching mapping, Spring Security stops at the first match. The
 * shipped patterns are disjoint, so no route changes — but two overlapping patterns would
 * now behave differently, and the narrower one has to come first.</p>
 */
@Configuration
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain gatewaySecurityWebFilterChain(
            ServerHttpSecurity http,
            GatewaySecurityProperties securityProperties,
            CorsConfigurationSource corsConfigurationSource) {

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                // Bearer tokens carry their own state; there is no session to keep and no
                // saved request to replay after a redirect that never happens.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                // Without this, a 401 raised here short-circuits before Spring Cloud
                // Gateway's own CORS handling and the browser reports an opaque CORS
                // failure instead of letting the SPA read {"error": "..."}.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(exchanges -> {
                    exchanges.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    securityProperties.getPublicPaths()
                            .forEach(pattern -> exchanges.pathMatchers(pattern).permitAll());
                    securityProperties.getRoleMappings().stream()
                            .filter(mapping -> mapping.getPattern() != null && !mapping.getRoles().isEmpty())
                            .forEach(mapping -> exchanges.pathMatchers(mapping.getPattern())
                                    .hasAnyAuthority(mapping.getRoles().toArray(String[]::new)));
                    exchanges.anyExchange().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        // A token that was presented and could not be verified is a
                        // different answer from no token at all, and the SPA tells them
                        // apart by this message.
                        .authenticationEntryPoint(JsonErrorResponses.entryPoint("Invalid or expired token"))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter())))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(JsonErrorResponses.entryPoint("Missing authentication token"))
                        .accessDeniedHandler(JsonErrorResponses.accessDeniedHandler("Insufficient permissions")))
                .build();
    }

    /**
     * Reuses the CORS configuration already written under
     * {@code spring.cloud.gateway.globalcors}, rather than restating the allowed origins
     * in a second place that could drift from the first.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(GlobalCorsProperties globalCorsProperties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        globalCorsProperties.getCorsConfigurations().forEach(source::registerCorsConfiguration);
        return source;
    }

    private ReactiveJwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        // Email is the platform's identity key (ADR-0012), so it is the principal name
        // here too; `sub` would be the correct choice and is deliberately deferred.
        converter.setPrincipalClaimName("email");
        return converter;
    }
}
