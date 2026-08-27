package com.ecommerce.user_service.controller;

import com.ecommerce.user_service.security.request.ChangePasswordRequest;
import com.ecommerce.user_service.security.request.VerifyPasswordRequest;
import com.ecommerce.user_service.security.response.MessageResponse;
import com.ecommerce.user_service.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Account self-service for the currently logged-in user, regardless of role. Lives under
 * {@code /api/users} (not {@code /api/auth}) so the gateway enforces a valid JWT cookie
 * before the request reaches here - the same protection {@link AddressController} relies on.
 */
@RestController
@RequestMapping("/api/users")
public class AccountController {

    @Autowired
    private AuthService authService;

    @PostMapping("/password/verify")
    public ResponseEntity<MessageResponse> verifyCurrentPassword(@Valid @RequestBody VerifyPasswordRequest request) {
        return ResponseEntity.ok(authService.verifyCurrentPassword(request));
    }

    @PutMapping("/password")
    public ResponseEntity<MessageResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(authService.changePassword(request));
    }
}
