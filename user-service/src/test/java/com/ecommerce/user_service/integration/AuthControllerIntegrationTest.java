package com.ecommerce.user_service.integration;

import com.ecommerce.user_service.payload.UserResponse;
import com.ecommerce.user_service.security.KeycloakAdminClient;
import com.ecommerce.user_service.security.KeycloakRealmRoleConverter;
import com.ecommerce.user_service.security.response.MessageResponse;
import com.ecommerce.user_service.security.response.UserInfoResponse;
import com.ecommerce.user_service.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * user-service's HTTP surface after ADR-0012.
 *
 * <p>Half of what this class used to assert is now asserted by its absence. There is no
 * {@code POST /api/auth/signin}, {@code /signup} or {@code /signout} to test, because the
 * platform issues no tokens and stores no credentials; the first nested class pins that
 * those paths are gone rather than merely undocumented, since a route that quietly came
 * back would be a route that mints tokens again.</p>
 *
 * <p>The rest covers where the administrative operations went. That move is the fix for
 * SEC-02 — they used to sit under {@code /api/auth/**}, which the gateway declares public —
 * so "these paths need ROLE_ADMIN" is the assertion, not an implementation detail.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Integration - user-service HTTP contract")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    private static RequestPostProcessor caller(String... roles) {
        return jwt()
                .jwt(builder -> builder
                        .claim("email", "caller@techzone.test")
                        .claim("preferred_username", "caller@techzone.test")
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    @Nested
    @DisplayName("the token-issuing endpoints are gone")
    class NoLongerIssuesTokens {

        @Test
        @DisplayName("POST /api/auth/signin is not a route any more")
        void signinIsGone() throws Exception {
            mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"adminPass\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST /api/auth/signup is not a route any more - this is where SEC-01 lived")
        void signupIsGone() throws Exception {
            // The payload below is the SEC-01 exploit verbatim: a public request naming its
            // own roles, which AuthServiceImpl mapped onto ROLE_ADMIN. It now answers 404.
            // Registration is Keycloak's page, ROLE_USER is the realm's default role, and
            // there is no request field that can ask for anything else.
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"attacker\",\"email\":\"attacker@example.com\","
                                    + "\"password\":\"password1\",\"roles\":[\"admin\"]}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST /api/auth/signout is not a route any more")
        void signoutIsGone() throws Exception {
            mockMvc.perform(post("/api/auth/signout")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("the caller's own profile")
    class OwnProfile {

        @Test
        @DisplayName("GET /api/auth/user returns the profile, and no token with it")
        void returnsOwnProfile() throws Exception {
            when(authService.getCurrentUserDetails()).thenReturn(
                    new UserInfoResponse(4L, "caller@techzone.test", "caller@techzone.test", List.of("ROLE_USER")));

            mockMvc.perform(get("/api/auth/user").with(caller("ROLE_USER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("caller@techzone.test"))
                    .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"))
                    // The sign-in response used to hand the SPA the token it had just
                    // minted. Nothing here has a token to give.
                    .andExpect(jsonPath("$.jwtToken").doesNotExist());
        }

        @Test
        @DisplayName("GET /api/auth/username returns preferred_username")
        void returnsUsername() throws Exception {
            when(authService.getUsername()).thenReturn("caller@techzone.test");

            mockMvc.perform(get("/api/auth/username").with(caller("ROLE_USER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a token whose signature does not verify is 401, not 500 (BUG-19)")
        void unverifiableTokenIsUnauthorized() throws Exception {
            // Replaces JwtUtilsTest.tamperedTokenThrowsInsteadOfReturningFalse, which pinned
            // the missing SignatureException catch in JwtUtils. The class is deleted; the
            // expectation it recorded is asserted here against the resource server.
            when(jwtDecoder.decode(anyString()))
                    .thenThrow(new BadJwtException("Signed JWT rejected: Invalid signature"));

            mockMvc.perform(get("/api/auth/user").header("Authorization", "Bearer forged.token.value"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("SEC-02 - user administration moved behind ROLE_ADMIN")
    class Administration {

        @Test
        @DisplayName("the seller list is no longer readable without a token")
        void sellerListNeedsAuthentication() throws Exception {
            // Before: GET /api/auth/sellers, under a prefix gateway.security declares public,
            // so anyone who could reach the port could read every seller account.
            mockMvc.perform(get("/api/admin/users/sellers")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a customer cannot read the seller list")
        void customerCannotReadSellerList() throws Exception {
            mockMvc.perform(get("/api/admin/users/sellers").with(caller("ROLE_USER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a seller cannot read the customer list either")
        void sellerCannotReadCustomerList() throws Exception {
            mockMvc.perform(get("/api/admin/users/customers").with(caller("ROLE_SELLER", "ROLE_USER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an administrator reads the seller list")
        void adminReadsSellerList() throws Exception {
            when(authService.getAllSellers(any())).thenReturn(new UserResponse());

            mockMvc.perform(get("/api/admin/users/sellers")
                            .with(caller("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("deleting a customer requires ROLE_ADMIN")
        void deleteRequiresAdmin() throws Exception {
            mockMvc.perform(delete("/api/admin/users/customers/4").with(caller("ROLE_USER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an administrator creates a seller")
        void adminCreatesSeller() throws Exception {
            when(authService.createUser(any())).thenReturn(new MessageResponse("Account created"));

            mockMvc.perform(post("/api/admin/users")
                            .with(caller("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", "seller2@example.com",
                                    "firstName", "Demo",
                                    "lastName", "Seller",
                                    "role", "ROLE_SELLER"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a malformed email is rejected by validation before reaching the service")
        void rejectsMalformedEmail() throws Exception {
            mockMvc.perform(post("/api/admin/users")
                            .with(caller("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", "not-an-email",
                                    "role", "ROLE_SELLER"))))
                    .andExpect(status().isBadRequest());
        }
    }
}
