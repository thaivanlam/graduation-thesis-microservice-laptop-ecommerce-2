package com.ecommerce.user_service.payload;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What an administrator sends to create a seller or a customer account.
 *
 * <p>Note what this carries and what it does not. It names <em>one</em> role, as a string,
 * and {@code AuthServiceImpl} checks it against a server-side allow-list of
 * {@code ROLE_SELLER} and {@code ROLE_USER} before it reaches Keycloak. The old
 * {@code SignupRequest} carried a {@code Set<String> roles} on a <em>public</em> endpoint and
 * mapped {@code "admin"} straight onto {@code ROLE_ADMIN} — that was SEC-01, and it is
 * closed by two separate things: this endpoint requires {@code ROLE_ADMIN} to call at all,
 * and even then it cannot grant {@code ROLE_ADMIN}.</p>
 *
 * <p>There is no password field either. The caller does not choose one: the account is
 * created with a temporary password that Keycloak forces the holder to replace at first
 * login, and the endpoint returns it once so the administrator can pass it on.</p>
 */
public class CreateUserRequest {

    @NotBlank
    @Email
    @Size(max = 50)
    private String email;

    @Size(max = 50)
    private String firstName;

    @Size(max = 50)
    private String lastName;

    /** {@code ROLE_SELLER} or {@code ROLE_USER}. Anything else is rejected. */
    @NotBlank
    private String role;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
