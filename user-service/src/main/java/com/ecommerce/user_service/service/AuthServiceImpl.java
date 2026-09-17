package com.ecommerce.user_service.service;

import com.ecommerce.user_service.exceptions.APIException;
import com.ecommerce.user_service.exceptions.ResourceNotFoundException;
import com.ecommerce.user_service.model.AppRole;
import com.ecommerce.user_service.model.Role;
import com.ecommerce.user_service.model.User;
import com.ecommerce.user_service.payload.CreateUserRequest;
import com.ecommerce.user_service.payload.UserDTO;
import com.ecommerce.user_service.payload.UserResponse;
import com.ecommerce.user_service.repositories.RoleRepository;
import com.ecommerce.user_service.repositories.UserRepository;
import com.ecommerce.user_service.security.KeycloakAdminClient;
import com.ecommerce.user_service.security.response.MessageResponse;
import com.ecommerce.user_service.security.response.UserInfoResponse;
import com.ecommerce.user_service.util.AuthUtil;
import jakarta.transaction.Transactional;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class AuthServiceImpl implements AuthService {

    /**
     * The only roles this platform's own API can grant. ADR-0012, decided deliberately:
     * letting the endpoint grant whatever the caller names would put "who may become an
     * administrator" back inside application code, which is the exact place the migration
     * is taking it out of. Behind the {@code ROLE_ADMIN} check on this path that would be
     * legitimate rather than a defect — but it would mean any future flaw in this one
     * handler is privilege escalation again. The allow-list makes that impossible rather
     * than unlikely.
     *
     * <p>Promoting an administrator is therefore a Keycloak console operation, outside the
     * application entirely. That is a cost, not a bonus: the dashboard can no longer do
     * something it could before, and console access becomes the thing protecting
     * {@code ROLE_ADMIN}.</p>
     */
    private static final Set<String> GRANTABLE_ROLES = Set.of("ROLE_SELLER", "ROLE_USER");

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ModelMapper modelMapper;

    @Autowired
    private NotificationProducer notificationProducer;

    @Autowired
    private AuthUtil authUtil;

    @Autowired
    private KeycloakAdminClient keycloakAdminClient;

    // ------------------------------------------------------------------
    // The caller's own profile
    // ------------------------------------------------------------------

    @Override
    public UserInfoResponse getCurrentUserDetails() {
        // loggedInUser() provisions the local row on first sight, so a customer who
        // registered on Keycloak's page can call this immediately after their first sign-in.
        User user = authUtil.loggedInUser();
        List<String> roles = user.getRoles().stream()
                .map(role -> role.getRoleName().name())
                .collect(Collectors.toList());
        return new UserInfoResponse(user.getUserId(), user.getUserName(), user.getEmail(), roles);
    }

    @Override
    public String getUsername() {
        return authUtil.loggedInUser().getUserName();
    }

    // ------------------------------------------------------------------
    // Administration (SEC-02: these moved out from under public /api/auth/**)
    // ------------------------------------------------------------------

    @Override
    public UserResponse getAllSellers(Pageable pageable) {
        return toResponse(userRepository.findByRoleName(AppRole.ROLE_SELLER, pageable));
    }

    @Override
    public UserResponse getAllCustomers(Pageable pageable) {
        return toResponse(userRepository.findByRoleName(AppRole.ROLE_USER, pageable));
    }

    @Override
    public MessageResponse createUser(CreateUserRequest request) {
        String requestedRole = request.getRole() == null ? "" : request.getRole().trim().toUpperCase();
        if (!GRANTABLE_ROLES.contains(requestedRole)) {
            throw new APIException("Role must be one of " + GRANTABLE_ROLES
                    + ". ROLE_ADMIN cannot be granted through the API - use the Keycloak console.");
        }

        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new APIException("A profile already exists for " + email);
        }

        String temporaryPassword = temporaryPassword();
        String keycloakId = keycloakAdminClient.createUser(
                email, request.getFirstName(), request.getLastName(), temporaryPassword);
        keycloakAdminClient.assignRealmRole(keycloakId, requestedRole);

        // The local profile row is written in the same operation, so a seller created here
        // exists in MySQL before their first sign-in. Just-in-time provisioning in AuthUtil
        // is the fallback for self-registered customers, not the only path.
        Role role = roleRepository.findByRoleName(AppRole.valueOf(requestedRole))
                .orElseThrow(() -> new APIException("Role " + requestedRole + " is not seeded locally"));
        User profile = new User(email, email);
        profile.setRoles(Set.of(role));
        userRepository.save(profile);

        notificationProducer.sendRegistrationEmail(email, email);

        // Returned once, to be handed to the account holder out of band. Keycloak forces a
        // change at first login. Not emailed: BUG-06 means a failed send is swallowed, and
        // account creation should not depend on a path that loses its own errors.
        return new MessageResponse("Account created for " + email
                + ". Temporary password (must be changed at first login): " + temporaryPassword);
    }

    @Override
    public MessageResponse deleteCustomer(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

        boolean isOnlyCustomer = user.getRoles().stream()
                .allMatch(role -> role.getRoleName() == AppRole.ROLE_USER);

        if (!isOnlyCustomer) {
            throw new APIException("Cannot delete user: Not a customer or has additional roles");
        }

        deleteEverywhere(user);
        return new MessageResponse("Customer deleted successfully");
    }

    @Override
    public MessageResponse deleteSeller(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

        boolean isSeller = user.getRoles().stream()
                .anyMatch(role -> role.getRoleName() == AppRole.ROLE_SELLER);

        if (!isSeller) {
            throw new APIException("Cannot delete user: Not a seller");
        }

        boolean isAdmin = user.getRoles().stream()
                .anyMatch(role -> role.getRoleName() == AppRole.ROLE_ADMIN);

        if (isAdmin) {
            throw new APIException("Cannot delete user: User has admin role");
        }

        deleteEverywhere(user);
        return new MessageResponse("Seller deleted successfully");
    }

    // ------------------------------------------------------------------

    /**
     * Removes both records. ADR-0012 names "two sources of truth about a user" as an
     * accepted cost and notes that nothing reconciles them; deleting from both here is the
     * one place that cost is paid down rather than incurred. It is still not transactional
     * across the two — if Keycloak answers and MySQL then fails, the account is gone and
     * the profile row is not.
     */
    private void deleteEverywhere(User user) {
        keycloakAdminClient.findUserIdByEmail(user.getEmail())
                .ifPresent(keycloakAdminClient::deleteUser);
        userRepository.delete(user);
    }

    private UserResponse toResponse(Page<User> page) {
        List<UserDTO> userDtos = page.getContent()
                .stream()
                .map(user -> modelMapper.map(user, UserDTO.class))
                .collect(Collectors.toList());

        UserResponse response = new UserResponse();
        response.setContent(userDtos);
        response.setPageNumber(page.getNumber());
        response.setPageSize(page.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setLastPage(page.isLast());
        return response;
    }

    private static String temporaryPassword() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        // The realm's password policy requires at least 8 characters; this is 16.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
