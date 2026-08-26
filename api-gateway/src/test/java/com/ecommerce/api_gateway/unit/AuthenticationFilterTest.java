package com.ecommerce.api_gateway.unit;

import com.ecommerce.api_gateway.security.AuthenticationFilter;
import com.ecommerce.api_gateway.security.GatewaySecurityProperties;
import com.ecommerce.api_gateway.security.JwtService;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the gateway's {@link AuthenticationFilter} - the single place where the
 * platform decides whether a request is allowed through and whether the caller holds the
 * role the route demands. Everything downstream trusts this filter, so its truth table is
 * spelled out here case by case.
 *
 * <p>The policy used in these tests mirrors gateway.security in
 * api-gateway/src/main/resources/application.yaml; a companion test
 * ({@code GatewaySecurityPolicyTest}) asserts that the deployed file still says the
 * same thing.</p>
 */
@DisplayName("Unit - AuthenticationFilter (api-gateway)")
class AuthenticationFilterTest {

    private static final String SECRET =
            "dGVjaHpvbmUtdGVzdC1vbmx5LWp3dC1zaWduaW5nLXNlY3JldC1ub3QtdXNlZC1pbi1hbnktcmVhbC1lbnYtMDE=";
    private static final String COOKIE_NAME = "springBootEcom";

    private AuthenticationFilter filter;
    private AtomicBoolean chainCalled;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.getPublicPaths().addAll(List.of(
                "/user-manager/api/auth/**",
                "/user-manager/api/public/**",
                "/product-manager/api/public/**",
                "/product-manager/images/**",
                "/order-manager/api/public/**",
                "/order-manager/api/internal/**"));
        properties.getRoleMappings().addAll(List.of(
                roleMapping("/product-manager/api/admin/**", List.of("ROLE_ADMIN")),
                roleMapping("/product-manager/api/seller/**", List.of("ROLE_SELLER")),
                roleMapping("/user-manager/api/admin/**", List.of("ROLE_ADMIN")),
                roleMapping("/order-manager/api/admin/**", List.of("ROLE_ADMIN")),
                roleMapping("/order-manager/api/seller/**", List.of("ROLE_ADMIN", "ROLE_SELLER"))));

        filter = new AuthenticationFilter(new JwtService(SECRET), properties, COOKIE_NAME);
        chainCalled = new AtomicBoolean(false);
        chain = exchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };
    }

    private static GatewaySecurityProperties.RoleMapping roleMapping(String pattern, List<String> roles) {
        GatewaySecurityProperties.RoleMapping mapping = new GatewaySecurityProperties.RoleMapping();
        mapping.setPattern(pattern);
        mapping.setRoles(roles);
        return mapping;
    }

    private static String tokenWithRoles(List<String> roles) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        long now = System.currentTimeMillis();
        JwtBuilder builder = Jwts.builder()
                .subject("tester")
                .claim("userId", 4)
                .claim("email", "tester@techzone.test")
                .claim("roles", roles)
                .issuedAt(new Date(now - 1000))
                .expiration(new Date(now + 60_000));
        return builder.signWith(key).compact();
    }

    private static String expiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("tester")
                .claim("roles", List.of("ROLE_USER"))
                .issuedAt(new Date(now - 120_000))
                .expiration(new Date(now - 60_000))
                .signWith(key)
                .compact();
    }

    private MockServerWebExchange exchangeFor(String path, Map<String, String> cookies) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        cookies.forEach((name, value) -> builder.cookie(new org.springframework.http.HttpCookie(name, value)));
        return MockServerWebExchange.from(builder.build());
    }

    private HttpStatus statusAfterFiltering(String path, Map<String, String> cookies) {
        MockServerWebExchange exchange = exchangeFor(path, cookies);
        filter.filter(exchange, chain).block();
        return HttpStatus.valueOf(exchange.getResponse().getStatusCode() == null
                ? 200
                : exchange.getResponse().getStatusCode().value());
    }

    @Nested
    @DisplayName("public routes")
    class PublicRoutes {

        @Test
        @DisplayName("the sign-in route is reachable without a token")
        void authRouteIsPublic() {
            statusAfterFiltering("/user-manager/api/auth/signin", Map.of());

            assertThat(chainCalled).isTrue();
        }

        @Test
        @DisplayName("the public catalogue is reachable without a token")
        void catalogueIsPublic() {
            statusAfterFiltering("/product-manager/api/public/products", Map.of());

            assertThat(chainCalled).isTrue();
        }

        @Test
        @DisplayName("product images are reachable without a token")
        void imagesArePublic() {
            statusAfterFiltering("/product-manager/images/katana.png", Map.of());

            assertThat(chainCalled).isTrue();
        }

        @Test
        @DisplayName("a CORS pre-flight is let through without a token")
        void preflightIsAllowed() {
            MockServerWebExchange exchange =
                    MockServerWebExchange.from(MockServerHttpRequest.options("/order-manager/api/admin/orders").build());

            filter.filter(exchange, chain).block();

            assertThat(chainCalled).isTrue();
        }

        @Test
        @DisplayName("SEC-10 characterisation: the internal stock API is on the public list")
        void internalRouteIsPublic() {
            // /order-manager/api/internal/** is declared public so that service-to-service
            // calls can pass through the gateway unauthenticated. Documented as SEC-10 in
            // docs/backend/known-defects.md; pinned so that restricting it is deliberate.
            statusAfterFiltering("/order-manager/api/internal/products/7", Map.of());

            assertThat(chainCalled).isTrue();
        }
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("a protected route without a cookie is refused with 401")
        void missingTokenIsUnauthorized() {
            HttpStatus status = statusAfterFiltering("/order-manager/api/carts/users/cart", Map.of());

            assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(chainCalled).isFalse();
        }

        @Test
        @DisplayName("a blank cookie counts as no token at all")
        void blankTokenIsUnauthorized() {
            HttpStatus status = statusAfterFiltering("/order-manager/api/carts/users/cart",
                    Map.of(COOKIE_NAME, " "));

            assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a token in the wrong cookie name is not accepted")
        void wrongCookieNameIsUnauthorized() {
            HttpStatus status = statusAfterFiltering("/order-manager/api/carts/users/cart",
                    Map.of("someOtherCookie", tokenWithRoles(List.of("ROLE_USER"))));

            assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an expired token is refused with 401")
        void expiredTokenIsUnauthorized() {
            HttpStatus status = statusAfterFiltering("/order-manager/api/carts/users/cart",
                    Map.of(COOKIE_NAME, expiredToken()));

            assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(chainCalled).isFalse();
        }

        @Test
        @DisplayName("garbage in the cookie is refused with 401 rather than crashing the gateway")
        void garbageTokenIsUnauthorized() {
            HttpStatus status = statusAfterFiltering("/order-manager/api/carts/users/cart",
                    Map.of(COOKIE_NAME, "not-a-jwt"));

            assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("BUG-19 characterisation: a forged signature escapes the filter instead of becoming a 401")
        void forgedTokenEscapesTheFilter() {
            // JwtService.isTokenValid catches MalformedJwtException, ExpiredJwtException,
            // UnsupportedJwtException and IllegalArgumentException - but not
            // SignatureException. A structurally valid token signed with the wrong key is
            // exactly the shape a forgery takes, so it propagates out of the filter and the
            // caller sees a 500 instead of the 401 this class otherwise returns.
            //
            // The request is still refused, so this is a robustness defect rather than an
            // authentication bypass - but it is the gateway, and a 500 here is the platform
            // telling an attacker their token was well-formed. Documented as BUG-19.
            //
            // When the catch clause is widened to JwtException, rewrite this as
            // assertThat(statusAfterFiltering(...)).isEqualTo(HttpStatus.UNAUTHORIZED).
            SecretKey otherKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(
                    "YW5vdGhlci10ZXN0LW9ubHktc2lnbmluZy1zZWNyZXQtdXNlZC10by1mb3JnZS1hLXRva2VuLTAwMDAwMDAwMA=="));
            String forged = Jwts.builder()
                    .subject("attacker")
                    .claim("roles", List.of("ROLE_ADMIN"))
                    .issuedAt(new Date(System.currentTimeMillis() - 1000))
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(otherKey)
                    .compact();

            assertThatThrownBy(() -> statusAfterFiltering("/product-manager/api/admin/products",
                    Map.of(COOKIE_NAME, forged)))
                    .isInstanceOf(SignatureException.class);

            assertThat(chainCalled).isFalse();
        }

        @Test
        @DisplayName("a valid token on a route with no role requirement is let through")
        void validTokenPassesUnrestrictedRoute() {
            HttpStatus status = statusAfterFiltering("/order-manager/api/carts/users/cart",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of("ROLE_USER"))));

            assertThat(status).isEqualTo(HttpStatus.OK);
            assertThat(chainCalled).isTrue();
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("a customer is refused the admin catalogue with 403")
        void customerCannotReachAdminRoute() {
            HttpStatus status = statusAfterFiltering("/product-manager/api/admin/products",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of("ROLE_USER"))));

            assertThat(status).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(chainCalled).isFalse();
        }

        @Test
        @DisplayName("an administrator reaches the admin catalogue")
        void adminReachesAdminRoute() {
            HttpStatus status = statusAfterFiltering("/product-manager/api/admin/products",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of("ROLE_ADMIN"))));

            assertThat(status).isEqualTo(HttpStatus.OK);
            assertThat(chainCalled).isTrue();
        }

        @Test
        @DisplayName("a seller is refused the admin catalogue")
        void sellerCannotReachAdminRoute() {
            HttpStatus status = statusAfterFiltering("/product-manager/api/admin/products",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of("ROLE_SELLER"))));

            assertThat(status).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a seller reaches the seller catalogue")
        void sellerReachesSellerRoute() {
            HttpStatus status = statusAfterFiltering("/product-manager/api/seller/products",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of("ROLE_SELLER"))));

            assertThat(status).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("an administrator is refused the seller catalogue - the roles are not hierarchical")
        void adminIsNotImplicitlyASeller() {
            HttpStatus status = statusAfterFiltering("/product-manager/api/seller/products",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of("ROLE_ADMIN"))));

            assertThat(status).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("seller order listing accepts either a seller or an administrator")
        void sellerOrdersAcceptBothRoles() {
            assertThat(statusAfterFiltering("/order-manager/api/seller/orders",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of("ROLE_SELLER"))))).isEqualTo(HttpStatus.OK);

            setUp();
            assertThat(statusAfterFiltering("/order-manager/api/seller/orders",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of("ROLE_ADMIN"))))).isEqualTo(HttpStatus.OK);

            setUp();
            assertThat(statusAfterFiltering("/order-manager/api/seller/orders",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of("ROLE_USER"))))).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a caller holding several roles passes if any one of them satisfies the route")
        void anyMatchingRoleIsEnough() {
            HttpStatus status = statusAfterFiltering("/user-manager/api/admin/customers",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of("ROLE_USER", "ROLE_ADMIN"))));

            assertThat(status).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("a token with no roles at all is refused on a role-guarded route")
        void rolelessTokenIsForbidden() {
            HttpStatus status = statusAfterFiltering("/product-manager/api/admin/products",
                    Map.of(COOKIE_NAME, tokenWithRoles(List.of())));

            assertThat(status).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("the filter runs early enough to guard every route")
        void filterRunsBeforeRouting() {
            assertThat(filter.getOrder()).isNegative();
        }
    }
}
