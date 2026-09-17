package com.ecommerce.product_service.util;

import com.ecommerce.product_service.exceptions.APIException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Who is calling, according to the access token Spring Security has already verified.
 *
 * <p>Before ADR-0012 this class read a cookie off the current request, verified an HS256
 * signature with a shared secret and parsed the claims itself — four such validators
 * existed across the platform, and BUG-19 was a missing catch clause in all four. It now
 * reads the {@link Jwt} that the resource-server filter chain put in the
 * {@link SecurityContextHolder}: by the time any of this runs the signature, the issuer and
 * the expiry have been checked against the realm's JWKS.</p>
 *
 * <p>{@code loggedInEmail()} keeps its signature, so its call sites did not change.
 * {@code loggedInUserId()} is gone: a Keycloak token has no numeric user id and inventing
 * one would be worse than not having it. Its only caller stamped {@code Product.sellerId},
 * which nothing ever read back — see the note on that field.</p>
 */
@Component
public class AuthUtil {

    public String loggedInEmail() {
        Jwt token = currentToken();
        String email = token.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            // A realm configured with email-as-username and email required should make this
            // unreachable; failing loudly is still better than attributing a product to a
            // caller whose identity is not actually known.
            email = token.getClaimAsString("preferred_username");
        }
        if (email == null || email.isBlank()) {
            throw new APIException("Missing authenticated user email");
        }
        return email;
    }

    private Jwt currentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new APIException("Missing authentication token");
        }
        return jwtAuthentication.getToken();
    }
}
