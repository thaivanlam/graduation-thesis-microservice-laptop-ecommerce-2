package com.ecommerce.user_service.security;

import com.ecommerce.user_service.exceptions.APIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The narrow slice of Keycloak's Admin REST API that user-service actually needs
 * (ADR-0012): create an account, grant it a realm role, list accounts by role, delete one.
 *
 * <p>Written against {@link RestClient} rather than {@code keycloak-admin-client} on
 * purpose. The official library brings a RESTEasy stack of its own and has to be kept in
 * step with the server version; four calls do not justify either. The shapes below are the
 * Admin API's own representations, not ours.</p>
 *
 * <p>It authenticates as the {@code techzone-admin-api} service account by client
 * credentials, and caches the token until shortly before it expires. That account holds
 * {@code manage-users}, {@code view-users} and {@code query-users} on {@code realm-management}
 * and nothing else — notably not {@code manage-realm}, so nothing reachable from this
 * service can alter the realm's roles or clients.</p>
 *
 * <p><strong>The client secret is never committed.</strong> It arrives as
 * {@code KEYCLOAK_ADMIN_CLIENT_SECRET}, the same pattern {@code STRIPE_SECRET_KEY} and
 * {@code MAIL_PASSWORD} already follow.</p>
 */
@Component
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    /** Renew this long before the token actually expires, so a call never races the clock. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    private String cachedToken;
    private Instant cachedTokenExpiresAt = Instant.EPOCH;

    public KeycloakAdminClient(
            RestClient.Builder restClientBuilder,
            @Value("${techzone.keycloak.base-url}") String baseUrl,
            @Value("${techzone.keycloak.realm:techzone}") String realm,
            @Value("${techzone.keycloak.admin-client-id:techzone-admin-api}") String clientId,
            @Value("${techzone.keycloak.admin-client-secret:}") String clientSecret) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    // ------------------------------------------------------------------
    // Users
    // ------------------------------------------------------------------

    /**
     * Creates an account with a temporary password the holder must replace at first login.
     *
     * <p>Temporary rather than an emailed reset link: the mail path swallows its own
     * failures (BUG-06), and creating an account should not depend on it.</p>
     *
     * @return the new account's Keycloak id ({@code sub})
     */
    public String createUser(String email, String firstName, String lastName, String temporaryPassword) {
        try {
            restClient.post()
                    .uri("/admin/realms/{realm}/users", realm)
                    .header("Authorization", "Bearer " + serviceAccountToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            // The realm is configured email-as-username, so these agree by
                            // construction; sending both keeps the intent explicit.
                            "username", email,
                            "email", email,
                            "firstName", firstName == null ? "" : firstName,
                            "lastName", lastName == null ? "" : lastName,
                            "enabled", true,
                            "emailVerified", true,
                            "credentials", List.of(Map.of(
                                    "type", "password",
                                    "value", temporaryPassword,
                                    "temporary", true))))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) {
                throw new APIException("An account already exists for " + email);
            }
            throw new APIException("Keycloak refused to create the account: " + ex.getStatusText());
        }

        return findUserIdByEmail(email)
                .orElseThrow(() -> new APIException("Account for " + email + " was created but could not be read back"));
    }

    public java.util.Optional<String> findUserIdByEmail(String email) {
        List<Map<String, Object>> found = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/admin/realms/{realm}/users")
                        .queryParam("email", email)
                        .queryParam("exact", true)
                        .build(realm))
                .header("Authorization", "Bearer " + serviceAccountToken())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {
                });

        if (found == null || found.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable((String) found.get(0).get("id"));
    }

    /**
     * Grants one realm role. Callers must have checked the role against the allow-list
     * first — this method does not, because it is also how {@code ROLE_USER} is granted.
     */
    public void assignRealmRole(String userId, String roleName) {
        Map<String, Object> role = restClient.get()
                .uri("/admin/realms/{realm}/roles/{role}", realm, roleName)
                .header("Authorization", "Bearer " + serviceAccountToken())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {
                });

        if (role == null) {
            throw new APIException("No such realm role: " + roleName);
        }

        restClient.post()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                .header("Authorization", "Bearer " + serviceAccountToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("id", role.get("id"), "name", role.get("name"))))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Deletes the account. Deliberately tolerant of an account that is already gone: the
     * local profile row and the Keycloak account are two sources of truth that nothing
     * reconciles (ADR-0012), so a caller deleting both should not be blocked by whichever
     * one went first.
     */
    public void deleteUser(String userId) {
        try {
            restClient.delete()
                    .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                    .header("Authorization", "Bearer " + serviceAccountToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                log.warn("Keycloak account {} was already gone when user-service tried to delete it", userId);
                return;
            }
            throw new APIException("Keycloak refused to delete the account: " + ex.getStatusText());
        }
    }

    // ------------------------------------------------------------------
    // Token
    // ------------------------------------------------------------------

    private synchronized String serviceAccountToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiresAt)) {
            return cachedToken;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {
                    });
        } catch (RestClientResponseException ex) {
            throw new APIException("Could not obtain a Keycloak service-account token. "
                    + "Is KEYCLOAK_ADMIN_CLIENT_SECRET set, and does it match the realm? (" + ex.getStatusText() + ")");
        }

        if (response == null || response.get("access_token") == null) {
            throw new APIException("Keycloak returned no access token for the service account");
        }

        cachedToken = (String) response.get("access_token");
        long expiresIn = response.get("expires_in") instanceof Number seconds ? seconds.longValue() : 60L;
        cachedTokenExpiresAt = Instant.now().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
        return cachedToken;
    }
}
