package com.ecommerce.order_service.integration;

import com.ecommerce.order_service.security.KeycloakRealmRoleConverter;
import com.ecommerce.order_service.service.AnalyticsService;
import com.ecommerce.order_service.service.CartService;
import com.ecommerce.order_service.service.OrderService;
import com.ecommerce.order_service.service.StripeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may reach order-service's paths, asserted against the service's own filter chain.
 *
 * <p>ADR-0012 made this service a resource server in its own right rather than a consumer
 * of the gateway's verdict — SEC-10 records that the container port is published on the
 * host, so a request arriving here need not have passed the gateway at all.</p>
 *
 * <p>What these cases do <em>not</em> cover is ownership, and that omission is the point of
 * the last nested class: authenticating a caller is all Keycloak can do, and SEC-07, SEC-08
 * and SEC-09 are still open after this migration.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Integration - order-service resource server policy")
class OrderServiceSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CartService cartService;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private StripeService stripeService;

    @MockitoBean
    private AnalyticsService analyticsService;

    /**
     * A caller holding exactly these realm roles. The authorities are derived by the real
     * {@link KeycloakRealmRoleConverter} rather than being handed over directly, so these
     * cases also fail if the {@code realm_access.roles} claim shape is read wrongly.
     */
    private static RequestPostProcessor caller(String... roles) {
        return jwt()
                .jwt(builder -> builder
                        .claim("email", "caller@techzone.test")
                        .claim("preferred_username", "caller@techzone.test")
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("a cart read with no token is refused with 401")
        void noTokenIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/carts/users/cart")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("BUG-19 fixed: a token whose signature does not verify is 401, not 500")
        void unverifiableTokenIsUnauthorized() throws Exception {
            // The deleted JwtService omitted SignatureException from its catch list, which is
            // exactly the shape a forgery takes, so a forged token propagated out as a 500.
            when(jwtDecoder.decode(anyString()))
                    .thenThrow(new BadJwtException("Signed JWT rejected: Invalid signature"));

            mockMvc.perform(get("/api/carts/users/cart").header("Authorization", "Bearer forged.token.value"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("the springBootEcom cookie is no longer a credential")
        void legacyCookieIsNotACredential() throws Exception {
            mockMvc.perform(get("/api/carts/users/cart")
                            .cookie(new jakarta.servlet.http.Cookie("springBootEcom", "eyJhbGciOiJIUzI1NiJ9.e30.x")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("any authenticated caller reaches their own cart")
        void authenticatedCallerReachesTheirCart() throws Exception {
            mockMvc.perform(get("/api/carts/users/cart").with(caller("ROLE_USER")))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("a customer is refused the admin order list")
        void customerCannotReachAdminOrders() throws Exception {
            mockMvc.perform(get("/api/admin/orders").with(caller("ROLE_USER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an administrator reaches the admin order list")
        void adminReachesAdminOrders() throws Exception {
            mockMvc.perform(get("/api/admin/orders").with(caller("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("seller order listing accepts either a seller or an administrator")
        void sellerOrdersAcceptBothRoles() throws Exception {
            mockMvc.perform(get("/api/seller/orders").with(caller("ROLE_SELLER", "ROLE_USER")))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/seller/orders").with(caller("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER")))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/seller/orders").with(caller("ROLE_USER")))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("what authentication does not buy")
    class OwnershipIsStillUnchecked {

        @Test
        @DisplayName("SEC-07 characterisation: any authenticated caller can list EVERY user's cart")
        void anyCallerCanListAllCarts() throws Exception {
            // /api/carts returns every cart in the database, not the caller's. ADR-0012 does
            // not close this and says so explicitly: Keycloak establishes who is calling and
            // has no view of whether a given cart belongs to them. The check is business code
            // in CartServiceImpl and remains to be written.
            //
            // Pinned so that the migration cannot be reported as having made the platform
            // secure, and so that fixing SEC-07 has to come back and change this line.
            //
            // The 302 is a second, unrelated defect on the same endpoint (BUG-12: it answers
            // FOUND for a successful read). Asserted as-is so this case fails if either
            // defect is fixed, rather than quietly passing through the other one.
            mockMvc.perform(get("/api/carts").with(caller("ROLE_USER")))
                    .andExpect(status().isFound());
        }
    }
}
