package com.ecommerce.user_service.security.response;

import java.util.List;

/**
 * The caller's own profile, as returned by {@code GET /api/auth/user}.
 *
 * <p>The {@code jwtToken} field is gone with ADR-0012. It existed because the sign-in
 * response handed the SPA the token it had just minted; the platform mints nothing now, and
 * the SPA gets its token from Keycloak. Returning one here would mean this service had a
 * token to give, which is exactly what it must not have.</p>
 */
public class UserInfoResponse {

    private Long id;
    private String username;
    private String email;
    private List<String> roles;

    public UserInfoResponse(Long id, String username, String email, List<String> roles) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.roles = roles;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }
}
