package com.ecommerce.api_gateway.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The gateway's truth table, after ADR-0012 moved authentication to Keycloak.
 *
 * <p>This is the single place the platform decides whether a request is allowed through and
 * whether the caller holds the role the route demands; everything downstream trusts that
 * decision, so it is spelled out case by case. It replaces the unit tests that covered
 * {@code AuthenticationFilter}, which no longer exists - the policy that filter applied is
 * now applied by the {@code SecurityWebFilterChain} in {@code GatewaySecurityConfig}, built
 * from the same {@code gateway.security} block. {@code GatewaySecurityPolicyTest} asserts
 * that the deployed file still says what these tests assume it says.</p>
 *
 * <p>Keycloak is not started here: {@link ReactiveJwtDecoder} is replaced, so what is under
 * test is the chain and the {@code realm_access.roles} claim shape rather than the realm
 * itself. A live realm is exercised by the suites in {@code tests/}.</p>
 *
 * <p>No route resolves - they point at {@code lb://PRODUCT-SERVICE} and no instance is
 * registered - so an <em>allowed</em> request ends in 503 rather than 200. "Not 401 and not
 * 403" is therefore the assertion for every case that should be let through: it means the
 * request survived the policy and reached routing.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Integration - gateway security chain (api-gateway)")
class GatewayBearerTokenSecurityTest {

    private static final String OPAQUE = "any-value-the-decoder-is-mocked";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    private static Jwt tokenWithRealmRoles(List<String> roles) {
        return Jwt.withTokenValue(OPAQUE)
                .header("alg", "RS256")
                .subject("0d2f6a8c-0000-4000-8000-000000000001")
                .claim("email", "tester@techzone.test")
                .claim("preferred_username", "tester@techzone.test")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private void callerHolds(String... roles) {
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.just(tokenWithRealmRoles(List.of(roles))));
    }

    private WebTestClient.ResponseSpec withToken(String path) {
        return webTestClient.get().uri(path).header("Authorization", "Bearer " + OPAQUE).exchange();
    }

    private WebTestClient.ResponseSpec withoutToken(String path) {
        return webTestClient.get().uri(path).exchange();
    }

    private static void isAllowedThrough(WebTestClient.ResponseSpec response, String because) {
        response.expectStatus().value(status -> assertThat(status).describedAs(because).isNotIn(401, 403));
    }

    @Nested
    @DisplayName("public routes")
    class PublicRoutes {

        @Test
        @DisplayName("the public catalogue is reachable without a token")
        void catalogueIsPublic() {
            isAllowedThrough(withoutToken("/product-manager/api/public/products"),
                    "public paths are permitted before any role rule is consulted");
        }

        @Test
        @DisplayName("product images are reachable without a token")
        void imagesArePublic() {
            isAllowedThrough(withoutToken("/product-manager/images/katana.png"), "images are public");
        }

        @Test
        @DisplayName("a CORS pre-flight is let through without a token")
        void preflightIsAllowed() {
            webTestClient.options().uri("/order-manager/api/admin/orders")
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "GET")
                    .exchange()
                    .expectStatus().value(status -> assertThat(status).isNotIn(401, 403));
        }

        @Test
        @DisplayName("SEC-10 characterisation: the internal stock API is still on the public list")
        void internalRouteIsPublic() {
            // /order-manager/api/internal/** is declared public so that service-to-service
            // calls can pass through the gateway unauthenticated. Documented as SEC-10 in
            // docs/backend/known-defects.md, and deliberately NOT closed by ADR-0012:
            // Keycloak authenticates a caller, it has no view of which callers a route
            // should have. Pinned so that restricting it stays a deliberate act.
            isAllowedThrough(withoutToken("/order-manager/api/internal/products/7"),
                    "SEC-10 is open by design until an ADR closes it");
        }

        @Test
        @DisplayName("SEC-12 characterisation: the Eureka registry is still on the public list")
        void eurekaIsPublic() {
            isAllowedThrough(withoutToken("/eureka/apps"),
                    "SEC-12 is open by design until an ADR closes it");
        }
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("a protected route with no credentials is refused with 401 and the JSON envelope")
        void missingTokenIsUnauthorized() {
            withoutToken("/order-manager/api/carts/users/cart")
                    .expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.error").isEqualTo("Missing authentication token");
        }

        @Test
        @DisplayName("BUG-19 fixed: a forged signature is refused with 401 rather than escaping as a 500")
        void forgedTokenIsUnauthorized() {
            // Was: JwtService.isTokenValid caught MalformedJwtException, ExpiredJwtException,
            // UnsupportedJwtException and IllegalArgumentException - but not
            // SignatureException, which is exactly the shape a forgery takes. It propagated
            // out of the filter and the caller saw a 500: the gateway telling an attacker
            // that their token was well-formed.
            //
            // ADR-0012 closes it by deleting the validator rather than widening its catch
            // list. Spring Security's decoder raises BadJwtException for a signature it
            // cannot verify, and the entry point answers it like any other bad credential.
            when(jwtDecoder.decode(anyString()))
                    .thenReturn(Mono.error(new BadJwtException("Signed JWT rejected: Invalid signature")));

            withToken("/product-manager/api/admin/products")
                    .expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.error").isEqualTo("Invalid or expired token");
        }

        @Test
        @DisplayName("an expired token is refused with 401")
        void expiredTokenIsUnauthorized() {
            when(jwtDecoder.decode(anyString())).thenReturn(Mono.error(
                    new JwtValidationException("Jwt expired", List.of(new OAuth2Error("invalid_token")))));

            withToken("/order-manager/api/carts/users/cart")
                    .expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.error").isEqualTo("Invalid or expired token");
        }

        @Test
        @DisplayName("a valid token on a route with no role requirement is let through")
        void validTokenPassesUnrestrictedRoute() {
            callerHolds("ROLE_USER");

            isAllowedThrough(withToken("/order-manager/api/carts/users/cart"),
                    "anyExchange().authenticated() is satisfied");
        }

        @Test
        @DisplayName("the springBootEcom cookie is no longer a credential")
        void legacyCookieIsNotACredential() {
            // ADR-0012 supersedes ADR-0004. The cookie is not merely deprecated: nothing in
            // the gateway reads it, so presenting one is the same as presenting nothing.
            webTestClient.get().uri("/order-manager/api/carts/users/cart")
                    .cookie("springBootEcom", "eyJhbGciOiJIUzI1NiJ9.e30.not-a-real-signature")
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.error").isEqualTo("Missing authentication token");
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("a customer is refused the admin catalogue with 403")
        void customerCannotReachAdminRoute() {
            callerHolds("ROLE_USER");

            withToken("/product-manager/api/admin/products")
                    .expectStatus().isForbidden()
                    .expectBody().jsonPath("$.error").isEqualTo("Insufficient permissions");
        }

        @Test
        @DisplayName("an administrator reaches the admin catalogue")
        void adminReachesAdminRoute() {
            callerHolds("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER");

            isAllowedThrough(withToken("/product-manager/api/admin/products"), "ROLE_ADMIN is mapped there");
        }

        @Test
        @DisplayName("a seller is refused the admin catalogue")
        void sellerCannotReachAdminRoute() {
            callerHolds("ROLE_SELLER", "ROLE_USER");

            withToken("/product-manager/api/admin/products").expectStatus().isForbidden();
        }

        @Test
        @DisplayName("a seller reaches the seller catalogue")
        void sellerReachesSellerRoute() {
            callerHolds("ROLE_SELLER", "ROLE_USER");

            isAllowedThrough(withToken("/product-manager/api/seller/products"), "ROLE_SELLER is mapped there");
        }

        @Test
        @DisplayName("ADR-0012 composite roles: an administrator still reaches the seller catalogue")
        void adminKeepsSellerRoutesThroughTheComposite() {
            // The route maps ROLE_SELLER alone, and the gateway does not treat the roles as
            // hierarchical - it never did. What changed is where the hierarchy is declared:
            // the realm makes ROLE_ADMIN include ROLE_SELLER and Keycloak expands it into
            // the token before signing, so an admin arrives already holding ROLE_SELLER.
            callerHolds("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER");

            isAllowedThrough(withToken("/product-manager/api/seller/products"),
                    "the composite carries ROLE_SELLER in an admin's token");
        }

        @Test
        @DisplayName("an admin token WITHOUT the composite is refused the seller catalogue")
        void adminWithoutCompositeLosesSellerRoutes() {
            // The negative half of the case above, and the reason the composite is called
            // load-bearing rather than tidy: this is what a realm that granted ROLE_ADMIN on
            // its own would produce - every seller endpoint 403ing for administrators, a
            // regression created by the migration in code the migration never touched.
            callerHolds("ROLE_ADMIN");

            withToken("/product-manager/api/seller/products").expectStatus().isForbidden();
        }

        @Test
        @DisplayName("seller order listing accepts either a seller or an administrator")
        void sellerOrdersAcceptBothRoles() {
            callerHolds("ROLE_SELLER", "ROLE_USER");
            isAllowedThrough(withToken("/order-manager/api/seller/orders"), "ROLE_SELLER is mapped there");

            callerHolds("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER");
            isAllowedThrough(withToken("/order-manager/api/seller/orders"), "ROLE_ADMIN is mapped there too");

            callerHolds("ROLE_USER");
            withToken("/order-manager/api/seller/orders").expectStatus().isForbidden();
        }

        @Test
        @DisplayName("a caller holding several roles passes if any one of them satisfies the route")
        void anyMatchingRoleIsEnough() {
            callerHolds("ROLE_USER", "ROLE_ADMIN");

            isAllowedThrough(withToken("/user-manager/api/admin/customers"),
                    "hasAnyAuthority, not hasAllAuthorities");
        }

        @Test
        @DisplayName("a token with no realm roles at all is refused on a role-guarded route")
        void rolelessTokenIsForbidden() {
            callerHolds();

            withToken("/product-manager/api/admin/products").expectStatus().isForbidden();
        }
    }
}
