package com.ecommerce.user_service.service;

import com.ecommerce.user_service.payload.CreateUserRequest;
import com.ecommerce.user_service.payload.UserResponse;
import com.ecommerce.user_service.security.response.MessageResponse;
import com.ecommerce.user_service.security.response.UserInfoResponse;
import org.springframework.data.domain.Pageable;

/**
 * What is left of this service once Keycloak owns authentication (ADR-0012).
 *
 * <p>Gone: {@code login}, {@code register}, {@code logoutUser}, {@code verifyCurrentPassword}
 * and {@code changePassword}. The platform issues no tokens and stores no credentials, so
 * signing in, signing up, signing out and changing a password are all Keycloak's, reached
 * by the SPA directly. Nothing here proxies them — a proxy would have to create accounts
 * with the admin service account, which is the endpoint SEC-01 lived in, rebuilt one layer
 * down.</p>
 *
 * <p>What remains is profile reads keyed on the caller's own token, and the administrative
 * operations that moved out from under the public {@code /api/auth/**} prefix (SEC-02).</p>
 */
public interface AuthService {

    /** The caller's own profile, from their token and the local row it maps to. */
    UserInfoResponse getCurrentUserDetails();

    /** The caller's {@code preferred_username}. */
    String getUsername();

    UserResponse getAllSellers(Pageable pageable);

    UserResponse getAllCustomers(Pageable pageable);

    /**
     * Creates an account in Keycloak and its local profile row in one operation.
     * Enforces the {@code ROLE_SELLER} / {@code ROLE_USER} allow-list — no endpoint in this
     * platform can grant {@code ROLE_ADMIN}.
     */
    MessageResponse createUser(CreateUserRequest request);

    MessageResponse deleteCustomer(Long userId);

    MessageResponse deleteSeller(Long userId);
}
