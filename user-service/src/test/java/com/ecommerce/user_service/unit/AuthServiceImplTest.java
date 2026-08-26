package com.ecommerce.user_service.unit;

import com.ecommerce.user_service.exceptions.APIException;
import com.ecommerce.user_service.exceptions.ResourceNotFoundException;
import com.ecommerce.user_service.model.AppRole;
import com.ecommerce.user_service.model.Role;
import com.ecommerce.user_service.model.User;
import com.ecommerce.user_service.payload.AuthenticationResult;
import com.ecommerce.user_service.repositories.RoleRepository;
import com.ecommerce.user_service.repositories.UserRepository;
import com.ecommerce.user_service.security.jwt.JwtUtils;
import com.ecommerce.user_service.security.request.LoginRequest;
import com.ecommerce.user_service.security.request.SignupRequest;
import com.ecommerce.user_service.security.response.MessageResponse;
import com.ecommerce.user_service.service.AuthServiceImpl;
import com.ecommerce.user_service.service.NotificationProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthServiceImpl}: sign-in, registration and the guards around
 * deleting an account. The password encoder, the repositories and the JWT component are
 * all test doubles, so what is under test is the decision logic alone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit - AuthServiceImpl")
class AuthServiceImplTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private NotificationProducer notificationProducer;

    @Spy
    private ModelMapper modelMapper = new ModelMapper();

    @InjectMocks
    private AuthServiceImpl authService;

    private static User user(Long id, String userName, String email, AppRole... roles) {
        User user = new User(userName, email, "hashed-password");
        user.setUserId(id);
        Set<Role> roleSet = new LinkedHashSet<>();
        for (AppRole role : roles) {
            roleSet.add(new Role(role));
        }
        user.setRoles(roleSet);
        return user;
    }

    private static SignupRequest signupRequest(String username, String email, String password, Set<String> roles) {
        SignupRequest request = new SignupRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPassword(password);
        request.setRoles(roles);
        return request;
    }

    private static LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("returns the identity, the token and the auth cookie for correct credentials")
        void signsInValidUser() {
            User buyer = user(4L, "buyer1", "buyer@techzone.test", AppRole.ROLE_USER);
            when(userRepository.findByUserName("buyer1")).thenReturn(Optional.of(buyer));
            when(passwordEncoder.matches("correct-password", "hashed-password")).thenReturn(true);
            when(jwtUtils.generateToken(buyer)).thenReturn("signed.jwt.token");
            when(jwtUtils.generateJwtCookie("signed.jwt.token"))
                    .thenReturn(ResponseCookie.from("springBootEcom", "signed.jwt.token").path("/").build());

            AuthenticationResult result = authService.login(loginRequest("buyer1", "correct-password"));

            assertThat(result.getResponse().getId()).isEqualTo(4L);
            assertThat(result.getResponse().getUsername()).isEqualTo("buyer1");
            assertThat(result.getResponse().getEmail()).isEqualTo("buyer@techzone.test");
            assertThat(result.getResponse().getRoles()).containsExactly("ROLE_USER");
            assertThat(result.getResponse().getJwtToken()).isEqualTo("signed.jwt.token");
            assertThat(result.getJwtCookie().getValue()).isEqualTo("signed.jwt.token");
        }

        @Test
        @DisplayName("rejects a wrong password without minting a token")
        void rejectsWrongPassword() {
            User buyer = user(4L, "buyer1", "buyer@techzone.test", AppRole.ROLE_USER);
            when(userRepository.findByUserName("buyer1")).thenReturn(Optional.of(buyer));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> authService.login(loginRequest("buyer1", "wrong-password")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Bad credentials");

            verify(jwtUtils, never()).generateToken(any(User.class));
        }

        @Test
        @DisplayName("rejects an unknown username with the same message as a wrong password")
        void rejectsUnknownUser() {
            when(userRepository.findByUserName("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(loginRequest("ghost", "whatever")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Bad credentials");
        }

        @Test
        @DisplayName("the failure is reported as 404, not 401 - a usability wart worth pinning")
        void reportsBadCredentialsAs404() {
            when(userRepository.findByUserName("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(loginRequest("ghost", "whatever")))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("carries every role of a multi-role account into the response")
        void carriesAllRoles() {
            User staff = user(5L, "boss", "boss@techzone.test", AppRole.ROLE_SELLER, AppRole.ROLE_ADMIN);
            when(userRepository.findByUserName("boss")).thenReturn(Optional.of(staff));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(jwtUtils.generateToken(staff)).thenReturn("signed.jwt.token");
            when(jwtUtils.generateJwtCookie(anyString()))
                    .thenReturn(ResponseCookie.from("springBootEcom", "signed.jwt.token").build());

            AuthenticationResult result = authService.login(loginRequest("boss", "correct-password"));

            assertThat(result.getResponse().getRoles())
                    .containsExactlyInAnyOrder("ROLE_SELLER", "ROLE_ADMIN");
        }
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("stores the password only as a hash")
        void hashesPassword() {
            when(userRepository.existsByUserName(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode("plain-text-password")).thenReturn("hashed-password");
            when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.of(new Role(AppRole.ROLE_USER)));

            authService.register(signupRequest("newbuyer", "new@techzone.test", "plain-text-password", null));

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getPassword()).isEqualTo("hashed-password");
            assertThat(captor.getValue().getPassword()).isNotEqualTo("plain-text-password");
        }

        @Test
        @DisplayName("defaults to ROLE_USER when no role is requested")
        void defaultsToUserRole() {
            when(userRepository.existsByUserName(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.of(new Role(AppRole.ROLE_USER)));

            ResponseEntity<MessageResponse> response =
                    authService.register(signupRequest("newbuyer", "new@techzone.test", "password", null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRoles()).extracting(Role::getRoleName)
                    .containsExactly(AppRole.ROLE_USER);
        }

        @Test
        @DisplayName("defaults to ROLE_USER when the requested role set is empty")
        void defaultsForEmptyRoleSet() {
            when(userRepository.existsByUserName(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.of(new Role(AppRole.ROLE_USER)));

            authService.register(signupRequest("newbuyer", "new@techzone.test", "password", new HashSet<>()));

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRoles()).extracting(Role::getRoleName)
                    .containsExactly(AppRole.ROLE_USER);
        }

        @Test
        @DisplayName("maps an unrecognised role name onto ROLE_USER rather than failing")
        void unknownRoleFallsBackToUser() {
            when(userRepository.existsByUserName(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.of(new Role(AppRole.ROLE_USER)));

            authService.register(signupRequest("newbuyer", "new@techzone.test", "password", Set.of("wizard")));

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRoles()).extracting(Role::getRoleName)
                    .containsExactly(AppRole.ROLE_USER);
        }

        @Test
        @DisplayName("SEC-01 characterisation: a caller can grant itself ROLE_ADMIN at sign-up")
        void selfServiceAdminIsAccepted() {
            // The registration endpoint honours whatever role set the request asks for, with
            // no authentication and no allow-list. This is the highest-severity finding in
            // docs/backend/known-defects.md (SEC-01). The test states the current behaviour
            // plainly so that closing the hole is an intentional, visible change.
            when(userRepository.existsByUserName(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(roleRepository.findByRoleName(AppRole.ROLE_ADMIN))
                    .thenReturn(Optional.of(new Role(AppRole.ROLE_ADMIN)));

            ResponseEntity<MessageResponse> response =
                    authService.register(signupRequest("attacker", "attacker@example.test", "password", Set.of("admin")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRoles()).extracting(Role::getRoleName)
                    .containsExactly(AppRole.ROLE_ADMIN);
        }

        @Test
        @DisplayName("refuses a username that is already taken")
        void refusesDuplicateUsername() {
            when(userRepository.existsByUserName("buyer1")).thenReturn(true);

            ResponseEntity<MessageResponse> response =
                    authService.register(signupRequest("buyer1", "new@techzone.test", "password", null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getMessage()).isEqualTo("Error: Username is already taken!");
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("refuses an email that is already registered")
        void refusesDuplicateEmail() {
            when(userRepository.existsByUserName(anyString())).thenReturn(false);
            when(userRepository.existsByEmail("buyer@techzone.test")).thenReturn(true);

            ResponseEntity<MessageResponse> response =
                    authService.register(signupRequest("newbuyer", "buyer@techzone.test", "password", null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getMessage()).isEqualTo("Error: Email is already taken!");
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("asks the notification service to send a welcome email")
        void sendsWelcomeEmail() {
            when(userRepository.existsByUserName(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.of(new Role(AppRole.ROLE_USER)));

            authService.register(signupRequest("newbuyer", "new@techzone.test", "password", null));

            verify(notificationProducer).sendRegistrationEmail("new@techzone.test", "newbuyer");
        }

        @Test
        @DisplayName("does not send a welcome email when registration is refused")
        void sendsNoEmailOnRefusal() {
            when(userRepository.existsByUserName("buyer1")).thenReturn(true);

            authService.register(signupRequest("buyer1", "new@techzone.test", "password", null));

            verify(notificationProducer, never()).sendRegistrationEmail(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("account deletion guards")
    class DeletionGuards {

        @Test
        @DisplayName("deletes a plain customer")
        void deletesCustomer() {
            User buyer = user(4L, "buyer1", "buyer@techzone.test", AppRole.ROLE_USER);
            when(userRepository.findById(4L)).thenReturn(Optional.of(buyer));

            MessageResponse response = authService.deleteCustomer(4L);

            assertThat(response.getMessage()).isEqualTo("Customer deleted successfully");
            verify(userRepository).delete(buyer);
        }

        @Test
        @DisplayName("refuses to delete an account that holds more than the customer role")
        void refusesToDeletePrivilegedAccountAsCustomer() {
            User staff = user(5L, "boss", "boss@techzone.test", AppRole.ROLE_USER, AppRole.ROLE_ADMIN);
            when(userRepository.findById(5L)).thenReturn(Optional.of(staff));

            assertThatThrownBy(() -> authService.deleteCustomer(5L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Not a customer or has additional roles");

            verify(userRepository, never()).delete(any(User.class));
        }

        @Test
        @DisplayName("deletes a seller")
        void deletesSeller() {
            User seller = user(6L, "seller1", "seller@techzone.test", AppRole.ROLE_SELLER);
            when(userRepository.findById(6L)).thenReturn(Optional.of(seller));

            MessageResponse response = authService.deleteSeller(6L);

            assertThat(response.getMessage()).isEqualTo("Seller deleted successfully");
            verify(userRepository).delete(seller);
        }

        @Test
        @DisplayName("refuses to delete a non-seller through the seller endpoint")
        void refusesNonSeller() {
            User buyer = user(4L, "buyer1", "buyer@techzone.test", AppRole.ROLE_USER);
            when(userRepository.findById(4L)).thenReturn(Optional.of(buyer));

            assertThatThrownBy(() -> authService.deleteSeller(4L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Not a seller");
        }

        @Test
        @DisplayName("refuses to delete a seller who is also an administrator")
        void refusesSellerWhoIsAdmin() {
            User staff = user(5L, "boss", "boss@techzone.test", AppRole.ROLE_SELLER, AppRole.ROLE_ADMIN);
            when(userRepository.findById(5L)).thenReturn(Optional.of(staff));

            assertThatThrownBy(() -> authService.deleteSeller(5L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("has admin role");

            verify(userRepository, never()).delete(any(User.class));
        }

        @Test
        @DisplayName("fails when the account does not exist")
        void failsForUnknownAccount() {
            when(userRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.deleteCustomer(404L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User not found with userId: 404");
        }
    }
}
