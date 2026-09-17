package com.ecommerce.user_service.util;

import com.ecommerce.user_service.model.User;
import com.ecommerce.user_service.repositories.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is calling, and the local profile row that belongs to them.
 *
 * <p>Two things changed at ADR-0012. The claims come from the {@link Jwt} the resource-server
 * chain already verified against the realm's JWKS, rather than from a cookie this class
 * parsed itself. And the local {@code user} row is no longer the source of the caller's
 * identity — Keycloak is — so it can be missing, and is created on the spot when it is.</p>
 *
 * <h2>Just-in-time provisioning</h2>
 *
 * <p>Somebody who registers on Keycloak's page has an account before this service has ever
 * heard of them; the first authenticated request they make is where the profile row appears.
 * Sellers created by an admin already have one, written by
 * {@code POST /api/admin/users} in the same operation, so this is the fallback path and not
 * the only one.</p>
 *
 * <p>The row is keyed on email, which is the identity key the whole data model uses
 * ({@code Order.email}, {@code Cart.userEmail}, {@code ProductSnapshot.sellerEmail}). ADR-0012
 * records why that is fragile and why moving to {@code sub} is deferred: it is a migration
 * across two databases and deserves its own decision.</p>
 */
@Component
public class AuthUtil {

    private final UserRepository userRepository;

    public AuthUtil(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public String loggedInEmail() {
        Jwt token = currentToken();
        String email = token.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            email = token.getClaimAsString("preferred_username");
        }
        if (email == null || email.isBlank()) {
            throw new UsernameNotFoundException("The access token carries no email claim");
        }
        return email;
    }

    public Long loggedInUserId() {
        return loggedInUser().getUserId();
    }

    /**
     * The caller's profile row, created from their token if this service has not seen them
     * before.
     *
     * <p>{@code REQUIRES_NEW} so that provisioning commits on its own. Without it, a first
     * request that goes on to fail for an unrelated reason would roll the new row back, and
     * the next request would provision again — harmless but pointless churn, and confusing
     * when reading the table.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User loggedInUser() {
        Jwt token = currentToken();
        String email = loggedInEmail();

        return userRepository.findByEmail(email).orElseGet(() -> {
            String username = token.getClaimAsString("preferred_username");
            User provisioned = new User(
                    username == null || username.isBlank() ? email : username,
                    email);
            return userRepository.save(provisioned);
        });
    }

    private Jwt currentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new UsernameNotFoundException("Missing authentication token");
        }
        return jwtAuthentication.getToken();
    }
}
