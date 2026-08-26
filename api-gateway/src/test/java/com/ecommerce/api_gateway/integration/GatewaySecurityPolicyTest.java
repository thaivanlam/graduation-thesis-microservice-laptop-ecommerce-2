package com.ecommerce.api_gateway.integration;

import com.ecommerce.api_gateway.security.GatewaySecurityProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test over the gateway's deployed configuration.
 *
 * <p>{@code AuthenticationFilterTest} proves the filter enforces whatever policy it is
 * handed. This test proves the policy actually shipped in
 * api-gateway/src/main/resources/application.yaml is the one the security model documents -
 * the two together close the loop. A route that quietly moves onto the public list, or a
 * role mapping that disappears, fails here.</p>
 *
 * @see <a href="../../../../../../../../../docs/architecture/security-model.md">docs/architecture/security-model.md</a>
 */
@DisplayName("Integration - deployed gateway security policy")
class GatewaySecurityPolicyTest {

    private static GatewaySecurityProperties policy;

    @BeforeAll
    static void bindDeployedConfiguration() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("gateway", new ClassPathResource("application.yaml"));
        sources.forEach(source -> environment.getPropertySources().addFirst(source));

        policy = Binder.get(environment)
                .bind("gateway.security", GatewaySecurityProperties.class)
                .orElseThrow(() -> new IllegalStateException("gateway.security is missing from application.yaml"));
    }

    @Test
    @DisplayName("authentication and the public catalogue are reachable without signing in")
    void publicPathsCoverAnonymousBrowsing() {
        assertThat(policy.getPublicPaths()).contains(
                "/user-manager/api/auth/**",
                "/product-manager/api/public/**",
                "/product-manager/images/**");
    }

    @Test
    @DisplayName("no admin or seller route has been placed on the public list")
    void publicPathsExposeNoPrivilegedRoute() {
        assertThat(policy.getPublicPaths())
                .noneMatch(path -> path.contains("/api/admin"))
                .noneMatch(path -> path.contains("/api/seller"));
    }

    @Test
    @DisplayName("every admin route is mapped to ROLE_ADMIN")
    void adminRoutesRequireAdmin() {
        assertThat(rolesFor("/product-manager/api/admin/**")).containsExactly("ROLE_ADMIN");
        assertThat(rolesFor("/user-manager/api/admin/**")).containsExactly("ROLE_ADMIN");
        assertThat(rolesFor("/order-manager/api/admin/**")).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("the seller catalogue is mapped to ROLE_SELLER")
    void sellerCatalogueRequiresSeller() {
        assertThat(rolesFor("/product-manager/api/seller/**")).containsExactly("ROLE_SELLER");
    }

    @Test
    @DisplayName("seller order listing admits sellers and administrators")
    void sellerOrdersAdmitBothRoles() {
        assertThat(rolesFor("/order-manager/api/seller/**"))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_SELLER");
    }

    @Test
    @DisplayName("every role mapping names at least one role")
    void everyMappingNamesARole() {
        assertThat(policy.getRoleMappings()).isNotEmpty();
        assertThat(policy.getRoleMappings()).allSatisfy(mapping -> {
            assertThat(mapping.getPattern()).isNotBlank();
            assertThat(mapping.getRoles()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("SEC-10 characterisation: the internal service-to-service route is public")
    void internalRouteIsPublic() {
        // Recorded rather than asserted as desirable: see SEC-10 in
        // docs/backend/known-defects.md.
        assertThat(policy.getPublicPaths()).contains("/order-manager/api/internal/**");
    }

    private List<String> rolesFor(String pattern) {
        return policy.getRoleMappings().stream()
                .filter(mapping -> pattern.equals(mapping.getPattern()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No role mapping declared for " + pattern))
                .getRoles();
    }
}
