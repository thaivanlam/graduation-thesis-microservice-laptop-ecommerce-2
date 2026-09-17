package com.ecommerce.api_gateway.unit;

import com.ecommerce.api_gateway.security.KeycloakRealmRoleConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the caller's roles come from after ADR-0012.
 *
 * <p>This is the one place the claim shape changed rather than merely moved. The platform's
 * own tokens carried a flat {@code roles} array; Keycloak nests them under
 * {@code realm_access.roles}. Reading the old shape out of a new token silently yields no
 * authorities at all — a 403 on every guarded route with nothing in the logs to say why —
 * so the shape is pinned here.</p>
 */
@DisplayName("Unit - KeycloakRealmRoleConverter (api-gateway)")
class KeycloakRealmRoleConverterTest {

    private static Jwt jwtWithClaims(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("0d2f6a8c-0000-4000-8000-000000000001")
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static List<String> authoritiesOf(Jwt jwt) {
        return new KeycloakRealmRoleConverter().convert(jwt)
                .map(GrantedAuthority::getAuthority)
                .collectList()
                .block();
    }

    @Test
    @DisplayName("roles are read from realm_access.roles, not from a flat roles claim")
    void readsRolesFromRealmAccess() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("ROLE_SELLER", "ROLE_USER"))));

        assertThat(authoritiesOf(jwt)).containsExactlyInAnyOrder("ROLE_SELLER", "ROLE_USER");
    }

    @Test
    @DisplayName("the ROLE_ prefix is used verbatim, so gateway.security role-mappings keep their values")
    void doesNotAddItsOwnPrefix() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("ROLE_ADMIN"))));

        assertThat(authoritiesOf(jwt)).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("a composite admin token carries the roles it includes, already expanded by Keycloak")
    void compositeRolesArriveExpanded() {
        // ROLE_ADMIN includes ROLE_SELLER includes ROLE_USER in the realm, and Keycloak
        // flattens the hierarchy into the token before signing it. Nothing in the gateway
        // has to know the hierarchy exists - which is the point of declaring it once, in
        // the realm, rather than in whichever rows a seed script happened to write.
        Jwt jwt = jwtWithClaims(Map.of("realm_access",
                Map.of("roles", List.of("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER", "offline_access"))));

        assertThat(authoritiesOf(jwt)).contains("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER");
    }

    @Test
    @DisplayName("a token with no realm_access yields no authorities rather than failing")
    void missingRealmAccessIsEmpty() {
        assertThat(authoritiesOf(jwtWithClaims(Map.of("email", "nobody@techzone.test")))).isEmpty();
    }

    @Test
    @DisplayName("a realm_access without a roles array yields no authorities")
    void realmAccessWithoutRolesIsEmpty() {
        assertThat(authoritiesOf(jwtWithClaims(Map.of("realm_access", Map.of())))).isEmpty();
    }

    @Test
    @DisplayName("the old flat roles claim is ignored, so a stale token cannot smuggle a role in")
    void flatRolesClaimIsIgnored() {
        Jwt jwt = jwtWithClaims(Map.of("roles", List.of("ROLE_ADMIN")));

        assertThat(authoritiesOf(jwt)).isEmpty();
    }
}
