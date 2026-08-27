package com.ecommerce.user_service.service;

import com.ecommerce.user_service.payload.AuthenticationResult;
import com.ecommerce.user_service.payload.UserResponse;
import com.ecommerce.user_service.security.request.ChangePasswordRequest;
import com.ecommerce.user_service.security.request.LoginRequest;
import com.ecommerce.user_service.security.request.SignupRequest;
import com.ecommerce.user_service.security.request.VerifyPasswordRequest;
import com.ecommerce.user_service.security.response.MessageResponse;
import com.ecommerce.user_service.security.response.UserInfoResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;

public interface AuthService {
    AuthenticationResult login(LoginRequest loginRequest);

    ResponseEntity<MessageResponse> register(@Valid SignupRequest signupRequest);

    UserInfoResponse getCurrentUserDetails(HttpServletRequest httpServletRequest);

    ResponseCookie logoutUser();

    String getUsername(HttpServletRequest httpServletRequest);

    UserResponse getAllSellers(Pageable pageable);

    UserResponse getAllCustomers(Pageable pageable);
    MessageResponse deleteCustomer(Long userId);
    MessageResponse deleteSeller(Long userId);

    MessageResponse verifyCurrentPassword(VerifyPasswordRequest verifyPasswordRequest);

    MessageResponse changePassword(ChangePasswordRequest changePasswordRequest);
}
