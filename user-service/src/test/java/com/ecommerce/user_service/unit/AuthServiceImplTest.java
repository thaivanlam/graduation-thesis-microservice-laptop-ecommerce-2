package com.ecommerce.user_service.unit;

import com.ecommerce.user_service.exceptions.APIException;
import com.ecommerce.user_service.model.AppRole;
import com.ecommerce.user_service.model.Role;
import com.ecommerce.user_service.model.User;
import com.ecommerce.user_service.payload.CreateUserRequest;
import com.ecommerce.user_service.repositories.RoleRepository;
import com.ecommerce.user_service.repositories.UserRepository;
import com.ecommerce.user_service.security.KeycloakAdminClient;
import com.ecommerce.user_service.security.response.MessageResponse;
import com.ecommerce.user_service.service.AuthServiceImpl;
import com.ecommerce.user_service.service.NotificationProducer;
import com.ecommerce.user_service.util.AuthUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.modelmapper.ModelMapper;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What user-service still decides about accounts, after ADR-0012 moved the deciding of
 * <em>who someone is</em> to Keycloak.
 *
 * <p>The old version of this class tested {@code login} and {@code register}: a BCrypt
 * comparison, a token, a cookie, and a {@code switch} that mapped the string {@code "admin"}
 * from a public request body onto {@code ROLE_ADMIN}. None of those methods exist any more.
 * What is left worth testing is the one decision the platform still makes about roles — the
 * allow-list on {@code POST /api/admin/users} — and it is the decision SEC-01 turns on, so
 * it is tested from several directions.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit - AuthServiceImpl (user-service)")
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private ModelMapper modelMapper;

    @Mock
    private NotificationProducer notificationProducer;

    @Mock
    private AuthUtil authUtil;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @InjectMocks
    private AuthServiceImpl authService;

    private static CreateUserRequest request(String email, String role) {
        CreateUserRequest request = new CreateUserRequest();
        request.setEmail(email);
        request.setFirstName("Demo");
        request.setLastName("Account");
        request.setRole(role);
        return request;
    }

    private void realmAccepts(String email, String keycloakId) {
        when(userRepository.existsByEmail(email)).thenReturn(false);
        when(keycloakAdminClient.createUser(eq(email), anyString(), anyString(), anyString()))
                .thenReturn(keycloakId);
        when(roleRepository.findByRoleName(any(AppRole.class)))
                .thenAnswer(call -> Optional.of(new Role(call.getArgument(0))));
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Nested
    @DisplayName("createUser - the role allow-list")
    class RoleAllowList {

        @Test
        @DisplayName("SEC-01: ROLE_ADMIN cannot be granted through the API, even by an administrator")
        void adminRoleIsNotGrantable() {
            // This is the regression test for the defect the whole migration started from.
            // The endpoint is already behind a ROLE_ADMIN check, so a caller reaching it is
            // an administrator - and it still refuses. The reasoning is in AuthServiceImpl:
            // an allow-list makes a future flaw in this handler impossible to turn into
            // privilege escalation, rather than merely unlikely. Promotion is a Keycloak
            // console operation, outside the application.
            assertThatThrownBy(() -> authService.createUser(request("newadmin@example.com", "ROLE_ADMIN")))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("ROLE_ADMIN cannot be granted");

            verifyNoInteractions(keycloakAdminClient);
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("an unknown role name is refused rather than defaulted")
        void unknownRoleIsRefused() {
            // The deleted signup path had `case "user": default:` - anything unrecognised
            // silently became a customer. Refusing is the safer failure: a typo in a role
            // name is now visible instead of producing an account with the wrong access.
            assertThatThrownBy(() -> authService.createUser(request("someone@example.com", "ROLE_SUPERUSER")))
                    .isInstanceOf(APIException.class);

            verifyNoInteractions(keycloakAdminClient);
        }

        @Test
        @DisplayName("the check is on the normalised value, so case and padding do not slip past it")
        void roleCheckIsNormalised() {
            assertThatThrownBy(() -> authService.createUser(request("x@example.com", "  role_admin  ")))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("ROLE_ADMIN cannot be granted");
        }

        @Test
        @DisplayName("ROLE_SELLER is granted, in Keycloak and in the local profile, in one operation")
        void sellerIsCreatedInBothStores() {
            realmAccepts("seller2@example.com", "f81d4fae-0000-4000-8000-00a0c91e6bf6");

            MessageResponse response = authService.createUser(request("seller2@example.com", "ROLE_SELLER"));

            verify(keycloakAdminClient).assignRealmRole("f81d4fae-0000-4000-8000-00a0c91e6bf6", "ROLE_SELLER");

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getEmail()).isEqualTo("seller2@example.com");
            assertThat(saved.getValue().getRoles())
                    .extracting(Role::getRoleName)
                    .containsExactly(AppRole.ROLE_SELLER);

            // The profile row is written here rather than left to just-in-time provisioning,
            // so an admin-created seller exists in MySQL before their first sign-in.
            assertThat(response.getMessage()).contains("seller2@example.com");
        }

        @Test
        @DisplayName("ROLE_USER is grantable too")
        void customerIsGrantable() {
            realmAccepts("customer@example.com", "f81d4fae-0000-4000-8000-00a0c91e6bf7");

            authService.createUser(request("customer@example.com", "ROLE_USER"));

            verify(keycloakAdminClient).assignRealmRole(anyString(), eq("ROLE_USER"));
        }
    }

    @Nested
    @DisplayName("createUser - the temporary password")
    class TemporaryPassword {

        @Test
        @DisplayName("the caller does not choose the password; it is generated and returned once")
        void passwordIsGeneratedNotSupplied() {
            // CreateUserRequest has no password field at all. Keycloak is told to mark the
            // one generated here temporary, so the holder must replace it at first login.
            // Returned in the response rather than emailed: BUG-06 means a failed send is
            // swallowed, and account creation should not depend on a path that loses errors.
            realmAccepts("seller3@example.com", "f81d4fae-0000-4000-8000-00a0c91e6bf8");

            MessageResponse response = authService.createUser(request("seller3@example.com", "ROLE_SELLER"));

            ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
            verify(keycloakAdminClient).createUser(eq("seller3@example.com"), anyString(), anyString(),
                    password.capture());

            assertThat(password.getValue()).hasSizeGreaterThanOrEqualTo(8);
            assertThat(response.getMessage()).contains(password.getValue());
        }

        @Test
        @DisplayName("two accounts do not get the same temporary password")
        void passwordsDiffer() {
            realmAccepts("a@example.com", "id-a");
            authService.createUser(request("a@example.com", "ROLE_SELLER"));

            realmAccepts("b@example.com", "id-b");
            authService.createUser(request("b@example.com", "ROLE_SELLER"));

            ArgumentCaptor<String> passwords = ArgumentCaptor.forClass(String.class);
            verify(keycloakAdminClient, org.mockito.Mockito.times(2))
                    .createUser(anyString(), anyString(), anyString(), passwords.capture());

            assertThat(passwords.getAllValues()).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("createUser - duplicates")
    class Duplicates {

        @Test
        @DisplayName("an email that already has a profile is refused before Keycloak is called")
        void duplicateEmailIsRefused() {
            when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.createUser(request("taken@example.com", "ROLE_SELLER")))
                    .isInstanceOf(APIException.class);

            verifyNoInteractions(keycloakAdminClient);
        }

        @Test
        @DisplayName("the email is normalised, so Taken@Example.com does not become a second account")
        void emailIsNormalised() {
            when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.createUser(request("  Taken@Example.com ", "ROLE_SELLER")))
                    .isInstanceOf(APIException.class);
        }
    }

    @Nested
    @DisplayName("deletion removes the account from both stores")
    class Deletion {

        @Test
        @DisplayName("deleting a customer deletes the Keycloak account as well as the profile row")
        void deleteCustomerRemovesBoth() {
            // ADR-0012 accepts "two sources of truth about a user" and notes that nothing
            // reconciles them. This is the one place that cost is paid down rather than
            // incurred - without it, a deleted customer could still sign in.
            User customer = new User("gone@example.com", "gone@example.com");
            customer.setUserId(4L);
            customer.setRoles(Set.of(new Role(AppRole.ROLE_USER)));
            when(userRepository.findById(4L)).thenReturn(Optional.of(customer));
            when(keycloakAdminClient.findUserIdByEmail("gone@example.com")).thenReturn(Optional.of("kc-4"));

            authService.deleteCustomer(4L);

            verify(keycloakAdminClient).deleteUser("kc-4");
            verify(userRepository).delete(customer);
        }

        @Test
        @DisplayName("a seller who is also an administrator is still refused")
        void adminSellerCannotBeDeleted() {
            User adminSeller = new User("admin@example.com", "admin@example.com");
            adminSeller.setUserId(1L);
            adminSeller.setRoles(Set.of(new Role(AppRole.ROLE_SELLER), new Role(AppRole.ROLE_ADMIN)));
            when(userRepository.findById(1L)).thenReturn(Optional.of(adminSeller));

            assertThatThrownBy(() -> authService.deleteSeller(1L)).isInstanceOf(APIException.class);

            verify(keycloakAdminClient, never()).deleteUser(anyString());
        }
    }
}
