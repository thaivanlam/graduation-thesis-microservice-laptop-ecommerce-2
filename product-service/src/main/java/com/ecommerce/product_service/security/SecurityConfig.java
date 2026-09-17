package com.ecommerce.product_service.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * product-service as an OIDC resource server (ADR-0012).
 *
 * <p>The service validates the caller's token itself rather than taking the gateway's word
 * for it. That is deliberate, not belt-and-braces: SEC-10 records that these container
 * ports are published on the host, so "it came through the gateway" is not a property any
 * service here can rely on.</p>
 *
 * <p>The rules mirror {@code gateway.security} in api-gateway/src/main/resources/application.yaml
 * with the routing prefix stripped, because that is the same policy seen from one hop
 * further in. Two paths are permitted that a reader should not mistake for oversights:</p>
 *
 * <ul>
 *   <li>{@code /api/internal/**} — order-service calls it directly, without a token, to read
 *       stock and decrement it. This is <strong>SEC-10</strong>, and ADR-0012 explicitly does
 *       not close it: Keycloak authenticates a caller, it has no view of which callers a
 *       route should have. Closing it needs its own decision about service-to-service
 *       credentials.</li>
 *   <li>{@code /actuator/**} — Prometheus scrapes it on the container network.</li>
 * </ul>
 *
 * <p>Nothing here decides whether the caller <em>owns</em> the product they are editing.
 * SEC-05's ownership half is untouched by this migration and remains to be written in
 * {@code ProductServiceImpl}.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                // Bearer tokens carry their own state; there is no session to keep.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/public/**",
                                "/api/internal/**",
                                "/images/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/actuator/**")
                        .permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/api/seller/**").hasAuthority("ROLE_SELLER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        // Email is the platform's identity key: Product.sellerEmail, Cart.userEmail and
        // Order.email are all keyed on it, so it is what AuthUtil.loggedInEmail() returns.
        converter.setPrincipalClaimName("email");
        return converter;
    }
}
