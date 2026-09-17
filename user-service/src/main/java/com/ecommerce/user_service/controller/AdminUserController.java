package com.ecommerce.user_service.controller;

import com.ecommerce.user_service.config.AppConstants;
import com.ecommerce.user_service.payload.CreateUserRequest;
import com.ecommerce.user_service.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration (ADR-0012). <strong>This is where SEC-02 is fixed.</strong>
 *
 * <p>These four operations — list sellers, list customers, delete either — used to live on
 * {@code AuthController} under {@code /api/auth/**}, which
 * {@code gateway.security.public-paths} declares public. They were therefore callable by
 * anybody who could reach the gateway, with no token at all. Moving them here puts them
 * under {@code /user-manager/api/admin/**}, for which the gateway already had a
 * {@code ROLE_ADMIN} rule, and user-service's own filter chain enforces the same thing
 * again on the port directly.</p>
 *
 * <p>{@code POST /api/admin/users} is new, and replaces what the admin dashboard used to do:
 * POST {@code roles: ["seller"]} at the public sign-up endpoint. It enforces a server-side
 * allow-list of {@code ROLE_SELLER} and {@code ROLE_USER} — no endpoint in this platform can
 * grant {@code ROLE_ADMIN}, whoever is asking. Promoting an administrator is a Keycloak
 * console operation, outside the application entirely.</p>
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    @Autowired
    private AuthService authService;

    @PostMapping
    public ResponseEntity<?> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(authService.createUser(request));
    }

    @GetMapping("/sellers")
    public ResponseEntity<?> getAllSellers(
            @RequestParam(name = "pageNumber", defaultValue = AppConstants.PAGE_NUMBER, required = false) Integer pageNumber) {
        return ResponseEntity.ok(authService.getAllSellers(pageOf(pageNumber)));
    }

    @GetMapping("/customers")
    public ResponseEntity<?> getAllCustomers(
            @RequestParam(name = "pageNumber", defaultValue = AppConstants.PAGE_NUMBER, required = false) Integer pageNumber) {
        return ResponseEntity.ok(authService.getAllCustomers(pageOf(pageNumber)));
    }

    @DeleteMapping("/customers/{userId}")
    public ResponseEntity<?> deleteCustomer(@PathVariable Long userId) {
        return ResponseEntity.ok(authService.deleteCustomer(userId));
    }

    @DeleteMapping("/sellers/{userId}")
    public ResponseEntity<?> deleteSeller(@PathVariable Long userId) {
        return ResponseEntity.ok(authService.deleteSeller(userId));
    }

    private static Pageable pageOf(Integer pageNumber) {
        Sort sortByAndOrder = Sort.by(AppConstants.SORT_USERS_BY).descending();
        return PageRequest.of(pageNumber, Integer.parseInt(AppConstants.PAGE_SIZE), sortByAndOrder);
    }
}
