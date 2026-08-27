package com.ecommerce.user_service.integration;

import com.ecommerce.user_service.controller.AccountController;
import com.ecommerce.user_service.exceptions.APIException;
import com.ecommerce.user_service.security.SecurityConfig;
import com.ecommerce.user_service.security.response.MessageResponse;
import com.ecommerce.user_service.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the account self-service web layer: request binding, bean
 * validation on the change-password payload, and the mapping from {@link APIException} to a
 * 400 response (via the {@code @RestControllerAdvice} that {@code @WebMvcTest} picks up
 * automatically).
 *
 * <p>The service layer is stubbed - whether a request is even authenticated is a gateway
 * concern, exercised separately in {@code tests/system/access-control.test.js} against the
 * real deployed policy.</p>
 */
@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
@DisplayName("Integration - AccountController HTTP contract")
class AccountControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    @DisplayName("POST /api/users/password/verify returns 200 with a confirmation message")
    void verifiesCurrentPassword() throws Exception {
        when(authService.verifyCurrentPassword(any())).thenReturn(new MessageResponse("Password verified"));

        mockMvc.perform(post("/api/users/password/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password verified"));
    }

    @Test
    @DisplayName("a blank current password is rejected by validation before reaching the service")
    void rejectsBlankCurrentPassword() throws Exception {
        mockMvc.perform(post("/api/users/password/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(authService, never()).verifyCurrentPassword(any());
    }

    @Test
    @DisplayName("an incorrect current password comes back as 400 with the service's reason")
    void reportsIncorrectPassword() throws Exception {
        when(authService.verifyCurrentPassword(any())).thenThrow(new APIException("Current password is incorrect"));

        mockMvc.perform(post("/api/users/password/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));
    }

    @Test
    @DisplayName("PUT /api/users/password changes the password and returns a confirmation message")
    void changesPassword() throws Exception {
        when(authService.changePassword(any())).thenReturn(new MessageResponse("Password changed successfully"));

        mockMvc.perform(put("/api/users/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"correct-password\",\"newPassword\":\"new-password\",\"confirmPassword\":\"new-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully"));
    }

    @Test
    @DisplayName("a new password shorter than 6 characters is rejected by validation")
    void rejectsShortNewPassword() throws Exception {
        mockMvc.perform(put("/api/users/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"correct-password\",\"newPassword\":\"123\",\"confirmPassword\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.newPassword").exists());

        verify(authService, never()).changePassword(any());
    }

    @Test
    @DisplayName("a mismatched confirmation is refused with the service's reason")
    void reportsMismatchedConfirmation() throws Exception {
        when(authService.changePassword(any()))
                .thenThrow(new APIException("New password and confirmation do not match"));

        mockMvc.perform(put("/api/users/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"correct-password\",\"newPassword\":\"new-password\",\"confirmPassword\":\"different-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("New password and confirmation do not match"));
    }
}
