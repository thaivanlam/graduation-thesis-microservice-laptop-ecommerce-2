package com.ecommerce.product_service.unit;

import com.ecommerce.product_service.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the product-service {@link JwtService}. This class is the only place in
 * the module that interprets a token minted by user-service, so the tests pin down the
 * claim shapes it must tolerate (roles as a JSON array and as a comma-separated string,
 * userId as a number and as text) plus the cookie it reads the token from.
 */
@DisplayName("Unit - JwtService (product-service)")
class JwtServiceTest {

    private static final String SECRET =
            "dGVjaHpvbmUtdGVzdC1vbmx5LWp3dC1zaWduaW5nLXNlY3JldC1ub3QtdXNlZC1pbi1hbnktcmVhbC1lbnYtMDE=";
    private static final String OTHER_SECRET =
            "YW5vdGhlci10ZXN0LW9ubHktc2lnbmluZy1zZWNyZXQtdXNlZC10by1mb3JnZS1hLXRva2VuLTAwMDAwMDAwMA==";
    private static final String COOKIE_NAME = "springBootEcom";

    private final JwtService jwtService = new JwtService(SECRET, COOKIE_NAME);

    private static String token(String secret, Map<String, Object> claims, long ttlMillis) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        long now = System.currentTimeMillis();
        JwtBuilder builder = Jwts.builder()
                .subject("tester")
                .issuedAt(new Date(now - 1000))
                .expiration(new Date(now + ttlMillis));
        claims.forEach(builder::claim);
        return builder.signWith(key).compact();
    }

    private static String validToken() {
        return token(SECRET,
                Map.of("userId", 42, "email", "buyer@techzone.test", "roles", List.of("ROLE_USER")),
                60_000);
    }

    @Nested
    @DisplayName("resolveToken")
    class ResolveToken {

        @Test
        @DisplayName("returns null when the request carries no cookies at all")
        void returnsNullWithoutCookies() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getCookies()).thenReturn(null);

            assertThat(jwtService.resolveToken(request)).isNull();
        }

        @Test
        @DisplayName("returns null when the auth cookie is absent among other cookies")
        void returnsNullWhenCookieMissing() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("theme", "dark")});

            assertThat(jwtService.resolveToken(request)).isNull();
        }

        @Test
        @DisplayName("returns null when the auth cookie is present but blank")
        void returnsNullWhenCookieBlank() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getCookies()).thenReturn(new Cookie[]{new Cookie(COOKIE_NAME, "   ")});

            assertThat(jwtService.resolveToken(request)).isNull();
        }

        @Test
        @DisplayName("picks the auth cookie out of the cookie array")
        void picksAuthCookie() {
            String jwt = validToken();
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getCookies()).thenReturn(new Cookie[]{
                    new Cookie("theme", "dark"),
                    new Cookie(COOKIE_NAME, jwt)
            });

            assertThat(jwtService.resolveToken(request)).isEqualTo(jwt);
        }
    }

    @Nested
    @DisplayName("isTokenValid")
    class IsTokenValid {

        @Test
        @DisplayName("accepts a token signed with the configured secret")
        void acceptsWellSignedToken() {
            assertThat(jwtService.isTokenValid(validToken())).isTrue();
        }

        @Test
        @DisplayName("BUG-19 characterisation: a forged signature escapes as an exception instead of returning false")
        void forgedTokenThrowsInsteadOfReturningFalse() {
            String forged = token(OTHER_SECRET, Map.of("userId", 1), 60_000);

            // isTokenValid catches MalformedJwtException, ExpiredJwtException,
            // UnsupportedJwtException and IllegalArgumentException, but NOT
            // SignatureException. A token with a valid structure and a wrong signature
            // therefore propagates out of the validity check; AuthUtil never gets to raise
            // its "Invalid or expired authentication token" APIException, and the caller
            // sees an untyped 500 instead of a 4xx.
            //
            // This assertion pins the current behaviour. When the catch clause is widened
            // to JwtException the test will fail and must be rewritten as
            // assertThat(jwtService.isTokenValid(forged)).isFalse().
            assertThatThrownBy(() -> jwtService.isTokenValid(forged))
                    .isInstanceOf(SignatureException.class);
        }

        @Test
        @DisplayName("rejects an expired token")
        void rejectsExpiredToken() {
            String expired = token(SECRET, Map.of("userId", 1), -60_000);

            assertThat(jwtService.isTokenValid(expired)).isFalse();
        }

        @Test
        @DisplayName("rejects malformed and empty input without throwing")
        void rejectsMalformedInput() {
            assertThat(jwtService.isTokenValid("not-a-jwt")).isFalse();
            assertThat(jwtService.isTokenValid("")).isFalse();
        }
    }

    @Nested
    @DisplayName("claim extraction")
    class ClaimExtraction {

        @Test
        @DisplayName("reads subject, email and numeric userId")
        void readsStandardClaims() {
            Claims claims = jwtService.parseClaims(validToken());

            assertThat(jwtService.extractUsername(claims)).isEqualTo("tester");
            assertThat(jwtService.extractEmail(claims)).isEqualTo("buyer@techzone.test");
            assertThat(jwtService.extractUserId(claims)).isEqualTo(42L);
        }

        @Test
        @DisplayName("returns null for an absent email claim")
        void returnsNullForMissingEmail() {
            Claims claims = jwtService.parseClaims(token(SECRET, Map.of("userId", 7), 60_000));

            assertThat(jwtService.extractEmail(claims)).isNull();
        }

        @Test
        @DisplayName("parses a userId that arrives as a string")
        void parsesStringUserId() {
            Claims claims = jwtService.parseClaims(token(SECRET, Map.of("userId", "77"), 60_000));

            assertThat(jwtService.extractUserId(claims)).isEqualTo(77L);
        }

        @Test
        @DisplayName("returns null for a userId that is neither a number nor numeric text")
        void returnsNullForUnparseableUserId() {
            Claims claims = jwtService.parseClaims(token(SECRET, Map.of("userId", "abc"), 60_000));

            assertThat(jwtService.extractUserId(claims)).isNull();
        }

        @Test
        @DisplayName("reads roles delivered as a JSON array")
        void readsRolesFromArray() {
            Claims claims = jwtService.parseClaims(
                    token(SECRET, Map.of("roles", List.of("ROLE_USER", "ROLE_SELLER")), 60_000));

            assertThat(jwtService.extractRoles(claims)).containsExactly("ROLE_USER", "ROLE_SELLER");
        }

        @Test
        @DisplayName("reads roles delivered as a comma-separated string, trimming the parts")
        void readsRolesFromCsv() {
            Claims claims = jwtService.parseClaims(
                    token(SECRET, Map.of("roles", "ROLE_USER, ROLE_ADMIN"), 60_000));

            assertThat(jwtService.extractRoles(claims)).containsExactly("ROLE_USER", "ROLE_ADMIN");
        }

        @Test
        @DisplayName("returns an empty list for a blank or absent roles claim")
        void returnsEmptyRoles() {
            assertThat(jwtService.extractRoles(jwtService.parseClaims(token(SECRET, Map.of("roles", "  "), 60_000))))
                    .isEmpty();
            assertThat(jwtService.extractRoles(jwtService.parseClaims(token(SECRET, Map.of("userId", 1), 60_000))))
                    .isEmpty();
        }
    }
}
