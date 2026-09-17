package com.ecommerce.product_service.integration;

import com.ecommerce.product_service.security.KeycloakRealmRoleConverter;
import com.ecommerce.product_service.service.AnalyticsService;
import com.ecommerce.product_service.service.CategoryService;
import com.ecommerce.product_service.service.ProductService;
import com.ecommerce.product_service.service.ProductSpecificationService;
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
 * Who may reach product-service's paths, asserted against the service's own filter chain.
 *
 * <p>ADR-0012 made this service a resource server in its own right rather than a consumer
 * of the gateway's verdict. That is not belt-and-braces: SEC-10 records that the container
 * port is published on the host, so a request arriving here need not have passed the
 * gateway at all. These cases therefore exercise the same policy the gateway applies, one
 * hop further in.</p>
 *
 * <p>The realm is not started. {@link JwtDecoder} is replaced and
 * {@code SecurityMockMvcRequestPostProcessors.jwt()} builds the authentication directly,
 * so what is under test is the rule set and the {@code realm_access.roles} claim shape.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Integration - product-service resource server policy")
class ProductServiceSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private ProductSpecificationService productSpecificationService;

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
    @DisplayName("paths that need no token")
    class Public {

        @Test
        @DisplayName("the public catalogue is readable without a token")
        void publicCatalogue() throws Exception {
            mockMvc.perform(get("/api/public/products")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("SEC-10 characterisation: the internal stock API is still unauthenticated")
        void internalApiIsUnauthenticated() throws Exception {
            // order-service calls this directly, with no token, to read and decrement stock.
            // ADR-0012 explicitly does not close it - Keycloak authenticates a caller, it has
            // no view of which callers a route should have - so it is pinned here rather than
            // quietly fixed. Closing it needs its own decision about service credentials.
            mockMvc.perform(get("/api/internal/products/7"))
                    .andExpect(status().is(org.springframework.http.HttpStatus.OK.value()));
        }
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("a guarded path with no token is refused with 401")
        void noTokenIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/seller/products")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("BUG-19 fixed: a token whose signature does not verify is 401, not 500")
        void unverifiableTokenIsUnauthorized() throws Exception {
            // The deleted JwtService caught MalformedJwtException, ExpiredJwtException,
            // UnsupportedJwtException and IllegalArgumentException but not SignatureException,
            // so a forged token - a structurally valid one signed with the wrong key -
            // propagated out and the caller saw a 500. This replaces JwtServiceTest, which
            // pinned that behaviour and went with the class it tested.
            when(jwtDecoder.decode(anyString()))
                    .thenThrow(new BadJwtException("Signed JWT rejected: Invalid signature"));

            mockMvc.perform(get("/api/seller/products").header("Authorization", "Bearer forged.token.value"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("the springBootEcom cookie is no longer a credential")
        void legacyCookieIsNotACredential() throws Exception {
            mockMvc.perform(get("/api/seller/products")
                            .cookie(new jakarta.servlet.http.Cookie("springBootEcom", "eyJhbGciOiJIUzI1NiJ9.e30.x")))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("a customer is refused the seller catalogue")
        void customerCannotReachSellerRoutes() throws Exception {
            mockMvc.perform(get("/api/seller/products").with(caller("ROLE_USER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a seller reaches the seller catalogue")
        void sellerReachesSellerRoutes() throws Exception {
            mockMvc.perform(get("/api/seller/products").with(caller("ROLE_SELLER", "ROLE_USER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("ADR-0012 composite roles: an administrator still reaches the seller catalogue")
        void adminKeepsSellerRoutesThroughTheComposite() throws Exception {
            // The rule names ROLE_SELLER alone. An admin passes it only because the realm
            // makes ROLE_ADMIN include ROLE_SELLER and Keycloak expands the composite into
            // the token before signing it. Without that, every seller endpoint would start
            // refusing administrators.
            mockMvc.perform(get("/api/seller/products")
                            .with(caller("ROLE_ADMIN", "ROLE_SELLER", "ROLE_USER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an admin token WITHOUT the composite loses the seller catalogue")
        void adminWithoutCompositeLosesSellerRoutes() throws Exception {
            mockMvc.perform(get("/api/seller/products").with(caller("ROLE_ADMIN")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a seller is refused the admin catalogue")
        void sellerCannotReachAdminRoutes() throws Exception {
            mockMvc.perform(get("/api/admin/products").with(caller("ROLE_SELLER", "ROLE_USER")))
                    .andExpect(status().isForbidden());
        }
    }
}
