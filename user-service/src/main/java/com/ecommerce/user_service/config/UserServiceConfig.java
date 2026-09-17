package com.ecommerce.user_service.config;

import com.ecommerce.user_service.model.AppRole;
import com.ecommerce.user_service.model.Role;
import com.ecommerce.user_service.model.User;
import com.ecommerce.user_service.repositories.RoleRepository;
import com.ecommerce.user_service.repositories.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Seeds the role table and the demo <em>profiles</em>.
 *
 * <p>What changed at ADR-0012: there is no {@code PasswordEncoder} bean and no password
 * anywhere in this file, because this service no longer stores credentials. The demo
 * accounts themselves — and their passwords — live in
 * {@code backend/keycloak/realm-techzone.json}, and the rows written here are the local
 * profile and address-owner records that pair with them.</p>
 *
 * <p><strong>The emails must match the realm import exactly.</strong> They are the join
 * between the two stores: {@link com.ecommerce.user_service.util.AuthUtil} looks a caller up
 * by the {@code email} claim, so a mismatch does not fail loudly — it silently provisions a
 * second, roleless profile on first sign-in and the demo seller stops owning the demo
 * catalogue. The same coupling is why {@code backend/seed-db/run-seed.sh} resolves
 * {@code seller1} by email rather than by username.</p>
 *
 * <p>The roles are still assigned here as well as in the realm. That is a genuine
 * duplication and a consequence ADR-0012 records as "two sources of truth about a user":
 * Keycloak's copy is what the gateway and the services enforce, this copy is what the admin
 * dashboard's seller and customer lists read. Nothing reconciles them.</p>
 */
@Configuration
public class UserServiceConfig {

    @Bean
    public CommandLineRunner initData(RoleRepository roleRepository, UserRepository userRepository) {
        return args -> {
            Role userRole = roleRepository.findByRoleName(AppRole.ROLE_USER)
                    .orElseGet(() -> roleRepository.save(new Role(AppRole.ROLE_USER)));
            Role sellerRole = roleRepository.findByRoleName(AppRole.ROLE_SELLER)
                    .orElseGet(() -> roleRepository.save(new Role(AppRole.ROLE_SELLER)));
            Role adminRole = roleRepository.findByRoleName(AppRole.ROLE_ADMIN)
                    .orElseGet(() -> roleRepository.save(new Role(AppRole.ROLE_ADMIN)));

            seedProfile(userRepository, "user1@example.com", Set.of(userRole));
            seedProfile(userRepository, "user2@example.com", Set.of(userRole));
            seedProfile(userRepository, "seller1@example.com", Set.of(sellerRole, userRole));
            // The admin's three roles mirror the realm's composite ROLE_ADMIN -> ROLE_SELLER
            // -> ROLE_USER. Enforcement comes from the token, not from these rows; they are
            // here so the dashboard's own lists show the same picture.
            seedProfile(userRepository, "admin@example.com", Set.of(userRole, sellerRole, adminRole));
        };
    }

    /**
     * The username is the email because the realm is configured email-as-username, so that
     * is what arrives in the {@code preferred_username} claim and what just-in-time
     * provisioning would have written anyway.
     */
    private static void seedProfile(UserRepository userRepository, String email, Set<Role> roles) {
        if (userRepository.existsByEmail(email)) {
            return;
        }
        User user = new User(email, email);
        user.setRoles(roles);
        userRepository.save(user);
    }
}
