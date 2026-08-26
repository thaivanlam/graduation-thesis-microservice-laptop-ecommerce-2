package com.ecommerce.user_service.integration;

import com.ecommerce.user_service.controller.AuthController;
import com.ecommerce.user_service.payload.AuthenticationResult;
import com.ecommerce.user_service.security.SecurityConfig;
import com.ecommerce.user_service.security.response.MessageResponse;
import com.ecommerce.user_service.security.response.UserInfoResponse;
import com.ecommerce.user_service.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the authentication web layer: request binding, bean validation on
 * the sign-up payload, the Set-Cookie header that carries the session, and the mapping from
 * a {@link ResponseStatusException} to an HTTP status.
 *
 * <p>The service layer is stubbed. The real {@link SecurityConfig} is imported, so the
 * filter chain these tests run through is the one the service actually deploys with -
 * including the fact that CSRF protection is disabled on it.</p>
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@DisplayName("Integration - AuthController HTTP contract")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    @DisplayName("POST /api/auth/signin returns the identity and sets the auth cookie")
    void signsIn() throws Exception {
        UserInfoResponse info = new UserInfoResponse(4L, "signed.jwt.token", "buyer1",
                "buyer@techzone.test", List.of("ROLE_USER"));
        ResponseCookie cookie = ResponseCookie.from("springBootEcom", "signed.jwt.token")
                .path("/").maxAge(86400).build();
        when(authService.login(any())).thenReturn(new AuthenticationResult(info, cookie));

        mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer1\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("springBootEcom=signed.jwt.token")))
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.username").value("buyer1"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    @Test
    @DisplayName("bad credentials come back as the status the service chose, with no cookie")
    void badCredentialsSetNoCookie() throws Exception {
        when(authService.login(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Bad credentials"));

        mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"whatever\"}"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("POST /api/auth/signup returns 200 with a confirmation message")
    void signsUp() throws Exception {
        when(authService.register(any()))
                .thenReturn(ResponseEntity.ok(new MessageResponse("User registered successfully")));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbuyer\",\"email\":\"new@techzone.test\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User registered successfully"));
    }

    @Test
    @DisplayName("a sign-up with a too-short username is rejected by validation before reaching the service")
    void rejectsShortUsername() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\",\"email\":\"new@techzone.test\",\"password\":\"password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.username").exists());

        verify(authService, never()).register(any());
    }

    @Test
    @DisplayName("a sign-up with a malformed email is rejected by validation")
    void rejectsMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbuyer\",\"email\":\"not-an-email\",\"password\":\"password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").exists());

        verify(authService, never()).register(any());
    }

    @Test
    @DisplayName("a sign-up with a too-short password is rejected by validation")
    void rejectsShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbuyer\",\"email\":\"new@techzone.test\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.password").exists());
    }

    @Test
    @DisplayName("a duplicate sign-up is answered with 400 and the reason")
    void rejectsDuplicateSignup() throws Exception {
        when(authService.register(any())).thenReturn(ResponseEntity.badRequest()
                .body(new MessageResponse("Error: Username is already taken!")));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer1\",\"email\":\"new@techzone.test\",\"password\":\"password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Error: Username is already taken!"));
    }

    @Test
    @DisplayName("POST /api/auth/signout clears the auth cookie")
    void signsOut() throws Exception {
        when(authService.logoutUser())
                .thenReturn(ResponseCookie.from("springBootEcom", "").path("/").maxAge(0).build());

        mockMvc.perform(post("/api/auth/signout"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(jsonPath("$.message").value("You've been signed out!"));
    }
}
