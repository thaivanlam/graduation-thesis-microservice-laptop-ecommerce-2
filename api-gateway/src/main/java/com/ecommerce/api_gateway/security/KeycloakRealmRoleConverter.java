package com.ecommerce.api_gateway.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the caller's roles out of a Keycloak access token.
 *
 * <p>Keycloak does not put roles in a flat {@code roles} claim the way the platform's own
 * tokens did. It nests them:</p>
 *
 * <pre>{@code { "realm_access": { "roles": ["ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER"] } } }</pre>
 *
 * <p>The realm names its roles with the {@code ROLE_} prefix already (ADR-0012), so the
 * strings are used verbatim as authorities and no prefix is added. That is what lets
 * {@code gateway.security.role-mappings} in application.yaml keep the values it has had
 * all along.</p>
 *
 * <p>Composite roles are expanded by Keycloak before the token is signed, so an admin's
 * token literally carries ROLE_SELLER and ROLE_USER as well — nothing here has to know
 * about the hierarchy.</p>
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Flux<GrantedAuthority>> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";

    @Override
    public Flux<GrantedAuthority> convert(Jwt jwt) {
        return Flux.fromIterable(realmRoles(jwt));
    }

    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS);
        if (realmAccess == null || !(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(role -> !role.isBlank())
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }
}
