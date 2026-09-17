package com.ecommerce.order_service.security;

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
 * order-service as an OIDC resource server (ADR-0012).
 *
 * <p>The service validates the caller's token itself rather than taking the gateway's word
 * for it — SEC-10 records that these container ports are published on the host, so "it came
 * through the gateway" is not a property any service here can rely on.</p>
 *
 * <p>The rules mirror {@code gateway.security} in api-gateway/src/main/resources/application.yaml
 * with the routing prefix stripped. Note that {@code /api/seller/**} accepts an
 * administrator as well as a seller, matching the gateway's mapping for the same paths;
 * the equivalent product-service rule does not, and that asymmetry predates this change.</p>
 *
 * <p>Nothing here decides whether the caller <em>owns</em> the cart or order they are
 * touching. SEC-07 (every user's cart is returned), SEC-08 (any authenticated account can
 * change an order's status) and SEC-09 (address update and delete verify no ownership) are
 * missing checks in business code, and Keycloak has no view of them. They survive this
 * migration unchanged and are listed as such in ADR-0012.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/public/**",
                                "/api/internal/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/actuator/**")
                        .permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/api/seller/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_SELLER")
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
        converter.setPrincipalClaimName("email");
        return converter;
    }
}
