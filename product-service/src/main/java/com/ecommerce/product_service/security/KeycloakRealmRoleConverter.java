package com.ecommerce.product_service.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Map;

/**
 * Reads the caller's roles out of a Keycloak access token (ADR-0012).
 *
 * <p>Keycloak nests them under {@code realm_access.roles} rather than exposing the flat
 * {@code roles} claim the platform's own tokens carried. The realm already spells its role
 * names with the {@code ROLE_} prefix, so the strings become authorities verbatim and no
 * prefix is added here.</p>
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
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
