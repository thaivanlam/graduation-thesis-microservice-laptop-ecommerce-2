package com.ecommerce.user_service.unit;

import com.ecommerce.user_service.model.AppRole;
import com.ecommerce.user_service.model.Role;
import com.ecommerce.user_service.model.User;
import com.ecommerce.user_service.security.jwt.JwtUtils;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtUtils}, the component that mints the token every other service
 * trusts. The claims asserted here are a contract: product-service and order-service read
 * userId, email and roles out of exactly these names.
 */
@DisplayName("Unit - JwtUtils (user-service)")
class JwtUtilsTest {

    private static final String SECRET =
            "dGVjaHpvbmUtdGVzdC1vbmx5LWp3dC1zaWduaW5nLXNlY3JldC1ub3QtdXNlZC1pbi1hbnktcmVhbC1lbnYtMDE=";
    private static final String COOKIE_NAME = "springBootEcom";
    private static final long ONE_HOUR = 3_600_000L;

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtUtils, "jwtCookie", COOKIE_NAME);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", ONE_HOUR);
    }

    private static User user(Long id, String userName, String email, AppRole... roles) {
        User user = new User(userName, email, "irrelevant-hash");
        user.setUserId(id);
        Set<Role> roleSet = new java.util.LinkedHashSet<>();
        for (AppRole role : roles) {
            roleSet.add(new Role(role));
        }
        user.setRoles(roleSet);
        return user;
    }

    @Nested
    @DisplayName("generateToken")
    class GenerateToken {

        @Test
        @DisplayName("puts the username in the subject and the identity in the claims")
        void carriesIdentity() {
            String token = jwtUtils.generateToken(user(4L, "buyer1", "buyer@techzone.test", AppRole.ROLE_USER));

            assertThat(jwtUtils.getUserNameFromJwtToken(token)).isEqualTo("buyer1");
            assertThat(jwtUtils.getUserIdFromJwtToken(token)).isEqualTo(4L);
            assertThat(jwtUtils.getEmailFromJwtToken(token)).isEqualTo("buyer@techzone.test");
        }

        @Test
        @DisplayName("carries every role the user holds")
        void carriesAllRoles() {
            String token = jwtUtils.generateToken(
                    user(4L, "boss", "boss@techzone.test", AppRole.ROLE_SELLER, AppRole.ROLE_ADMIN));

            assertThat(jwtUtils.getRolesFromJwtToken(token))
                    .containsExactlyInAnyOrder("ROLE_SELLER", "ROLE_ADMIN");
        }

        @Test
        @DisplayName("produces an empty role list for a user with no roles")
        void handlesRolelessUser() {
            String token = jwtUtils.generateToken(user(4L, "nobody", "nobody@techzone.test"));

            assertThat(jwtUtils.getRolesFromJwtToken(token)).isEmpty();
        }

        @Test
        @DisplayName("issues a token that is valid now and expires after the configured lifetime")
        void issuesTokenWithLifetime() {
            String token = jwtUtils.generateToken(4L, "buyer1", "buyer@techzone.test", List.of("ROLE_USER"));

            assertThat(jwtUtils.validateJwtToken(token)).isTrue();
            long lifetimeMs = jwtUtils.getClaimsFromJwtToken(token).getExpiration().getTime()
                    - jwtUtils.getClaimsFromJwtToken(token).getIssuedAt().getTime();
            assertThat(lifetimeMs).isBetween(ONE_HOUR - 2_000, ONE_HOUR + 2_000);
        }

        @Test
        @DisplayName("two tokens for the same user are both valid")
        void reissueIsValid() {
            User user = user(4L, "buyer1", "buyer@techzone.test", AppRole.ROLE_USER);

            assertThat(jwtUtils.validateJwtToken(jwtUtils.generateToken(user))).isTrue();
            assertThat(jwtUtils.validateJwtToken(jwtUtils.generateToken(user))).isTrue();
        }
    }

    @Nested
    @DisplayName("validateJwtToken")
    class ValidateJwtToken {

        @Test
        @DisplayName("BUG-19 characterisation: a tampered signature escapes as an exception instead of returning false")
        void tamperedTokenThrowsInsteadOfReturningFalse() {
            // validateJwtToken catches MalformedJwtException, ExpiredJwtException,
            // UnsupportedJwtException and IllegalArgumentException, but not
            // SignatureException. A structurally valid token with a wrong signature - the
            // shape a forgery actually takes - therefore propagates out of the check, and
            // the caller sees a 500 where a 401 belongs. The same gap exists in
            // product-service and order-service JwtService.
            //
            // Pinned as current behaviour; widening the catch to JwtException must turn this
            // into assertThat(jwtUtils.validateJwtToken(tampered)).isFalse().
            String token = jwtUtils.generateToken(user(4L, "buyer1", "buyer@techzone.test", AppRole.ROLE_USER));
            String tampered = token.substring(0, token.lastIndexOf('.') + 1)
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

            assertThatThrownBy(() -> jwtUtils.validateJwtToken(tampered))
                    .isInstanceOf(SignatureException.class);
        }

        @Test
        @DisplayName("rejects malformed, empty and null input without throwing")
        void rejectsGarbage() {
            assertThat(jwtUtils.validateJwtToken("not-a-jwt")).isFalse();
            assertThat(jwtUtils.validateJwtToken("")).isFalse();
            assertThat(jwtUtils.validateJwtToken(null)).isFalse();
        }

        @Test
        @DisplayName("rejects a token whose lifetime has already elapsed")
        void rejectsExpiredToken() {
            ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", -1_000L);
            String expired = jwtUtils.generateToken(user(4L, "buyer1", "buyer@techzone.test", AppRole.ROLE_USER));

            assertThat(jwtUtils.validateJwtToken(expired)).isFalse();
        }
    }

    @Nested
    @DisplayName("cookie handling")
    class CookieHandling {

        @Test
        @DisplayName("reads the token out of the configured cookie")
        void readsTokenFromCookie() {
            String token = jwtUtils.generateToken(user(4L, "buyer1", "buyer@techzone.test", AppRole.ROLE_USER));
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("theme", "dark"), new Cookie(COOKIE_NAME, token));

            assertThat(jwtUtils.getJwtFromCookies(request)).isEqualTo(token);
        }

        @Test
        @DisplayName("returns null when the request carries no auth cookie")
        void returnsNullWithoutCookie() {
            HttpServletRequest request = new MockHttpServletRequest();

            assertThat(jwtUtils.getJwtFromCookies(request)).isNull();
        }

        @Test
        @DisplayName("BUG-03 characterisation: the cookie outlives the token by 23 hours")
        void cookieOutlivesToken() {
            // The token lifetime comes from spring.app.jwtExpirationMs (one hour in this
            // configuration) but the cookie is always written with a 24-hour Max-Age, so the
            // browser keeps sending a token the server will reject. Documented as BUG-03.
            ResponseCookie cookie = jwtUtils.generateJwtCookie("some-token");

            assertThat(cookie.getMaxAge().toHours()).isEqualTo(24);
            assertThat(ONE_HOUR / 3_600_000L).isEqualTo(1);
        }

        @Test
        @DisplayName("SEC-13 characterisation: the auth cookie is neither HttpOnly nor Secure")
        void cookieLacksProtectiveFlags() {
            // Documented as SEC-13: the token is readable from JavaScript and is sent over
            // plain HTTP. Pinned here so hardening the flags is a visible, deliberate change.
            ResponseCookie cookie = jwtUtils.generateJwtCookie("some-token");

            assertThat(cookie.isHttpOnly()).isFalse();
            assertThat(cookie.isSecure()).isFalse();
            assertThat(cookie.getPath()).isEqualTo("/");
        }

        @Test
        @DisplayName("the sign-out cookie clears the value on the same path")
        void cleanCookieClearsValue() {
            ResponseCookie cookie = jwtUtils.getCleanJwtCookie();

            assertThat(cookie.getName()).isEqualTo(COOKIE_NAME);
            assertThat(cookie.getValue()).isNullOrEmpty();
            assertThat(cookie.getPath()).isEqualTo("/");
        }
    }
}
