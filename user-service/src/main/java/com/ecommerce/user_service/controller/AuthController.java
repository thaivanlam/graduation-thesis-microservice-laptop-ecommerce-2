package com.ecommerce.user_service.controller;

import com.ecommerce.user_service.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is left under {@code /api/auth} after ADR-0012: two reads of the caller's own
 * profile, backed by the access token they already hold.
 *
 * <p>Deliberately absent:</p>
 *
 * <ul>
 *   <li>{@code POST /signin} — the SPA gets a token from Keycloak by Authorization Code +
 *       PKCE. Nothing here verifies a password, because nothing here stores one.</li>
 *   <li>{@code POST /signup} — registration is Keycloak's page. This is where SEC-01 lived:
 *       the payload carried {@code roles} and {@code "admin"} mapped onto
 *       {@code ROLE_ADMIN}. The fix is not that the field is now validated more carefully,
 *       it is that there is no field, and no endpoint. A self-registering user receives the
 *       realm's default role, {@code ROLE_USER}, and cannot ask for more.</li>
 *   <li>{@code POST /signout} — there is no cookie to clear. The SPA calls Keycloak's
 *       end-session endpoint, which actually revokes the session rather than dropping a
 *       client-side credential on the floor (part of SEC-13).</li>
 *   <li>{@code GET /sellers}, {@code GET /customers}, {@code DELETE /customers/{id}},
 *       {@code DELETE /sellers/{id}} — these were administrative operations sitting under a
 *       prefix the gateway declares public, reachable by anyone: SEC-02. They moved to
 *       {@link AdminUserController} at {@code /api/admin/users}, behind {@code ROLE_ADMIN}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @GetMapping("/username")
    public ResponseEntity<?> currentUserName() {
        return ResponseEntity.ok(authService.getUsername());
    }

    @GetMapping("/user")
    public ResponseEntity<?> getUserDetails() {
        return ResponseEntity.ok(authService.getCurrentUserDetails());
    }
}
