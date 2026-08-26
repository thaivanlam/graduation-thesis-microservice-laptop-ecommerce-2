package com.ecommerce.user_service.integration;

import com.ecommerce.user_service.model.AppRole;
import com.ecommerce.user_service.model.Role;
import com.ecommerce.user_service.model.User;
import com.ecommerce.user_service.repositories.RoleRepository;
import com.ecommerce.user_service.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the identity persistence layer, running against H2.
 *
 * <p>The datasource configured in src/test/resources/application.yaml is used as-is
 * (replace = NONE) because the User entity maps to a table called "user", which H2 treats
 * as a keyword unless the connection URL says otherwise.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Integration - identity persistence")
class UserRepositoryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Role userRole;
    private Role sellerRole;
    private Role adminRole;

    @BeforeEach
    void seedIdentities() {
        userRole = roleRepository.save(new Role(AppRole.ROLE_USER));
        sellerRole = roleRepository.save(new Role(AppRole.ROLE_SELLER));
        adminRole = roleRepository.save(new Role(AppRole.ROLE_ADMIN));

        userRepository.save(user("buyer1", "buyer1@techzone.test", userRole));
        userRepository.save(user("buyer2", "buyer2@techzone.test", userRole));
        userRepository.save(user("seller1", "seller1@techzone.test", sellerRole));
        userRepository.save(user("boss", "boss@techzone.test", sellerRole, adminRole));
        userRepository.flush();
    }

    private static User user(String userName, String email, Role... roles) {
        User user = new User(userName, email, "$2a$10$hashedpasswordplaceholder");
        user.setRoles(new LinkedHashSet<>(Set.of(roles)));
        return user;
    }

    @Test
    @DisplayName("stores a user and reads the roles back eagerly")
    void storesUserWithRoles() {
        User stored = userRepository.findByUserName("boss").orElseThrow();

        assertThat(stored.getUserId()).isNotNull();
        assertThat(stored.getRoles()).extracting(Role::getRoleName)
                .containsExactlyInAnyOrder(AppRole.ROLE_SELLER, AppRole.ROLE_ADMIN);
    }

    @Test
    @DisplayName("findByUserName is empty for an unknown name rather than throwing")
    void unknownUserNameIsEmpty() {
        assertThat(userRepository.findByUserName("ghost")).isEmpty();
    }

    @Test
    @DisplayName("existsByUserName and existsByEmail answer the registration checks")
    void answersExistenceChecks() {
        assertThat(userRepository.existsByUserName("buyer1")).isTrue();
        assertThat(userRepository.existsByUserName("ghost")).isFalse();
        assertThat(userRepository.existsByEmail("buyer1@techzone.test")).isTrue();
        assertThat(userRepository.existsByEmail("ghost@techzone.test")).isFalse();
    }

    @Test
    @DisplayName("the unique constraint on username is enforced by the database, not only by the service")
    void rejectsDuplicateUserName() {
        assertThatThrownBy(() -> {
            userRepository.save(user("buyer1", "different@techzone.test", userRole));
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("the unique constraint on email is enforced by the database")
    void rejectsDuplicateEmail() {
        assertThatThrownBy(() -> {
            userRepository.save(user("different", "buyer1@techzone.test", userRole));
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("findByRoleName pages the customers")
    void pagesCustomers() {
        Page<User> customers = userRepository.findByRoleName(AppRole.ROLE_USER,
                PageRequest.of(0, 10, Sort.by("userId")));

        assertThat(customers.getTotalElements()).isEqualTo(2);
        assertThat(customers.getContent()).extracting(User::getUserName)
                .containsExactlyInAnyOrder("buyer1", "buyer2");
    }

    @Test
    @DisplayName("findByRoleName pages the sellers, including one who is also an administrator")
    void pagesSellers() {
        Page<User> sellers = userRepository.findByRoleName(AppRole.ROLE_SELLER,
                PageRequest.of(0, 10, Sort.by("userId")));

        assertThat(sellers.getContent()).extracting(User::getUserName)
                .containsExactlyInAnyOrder("seller1", "boss");
    }

    @Test
    @DisplayName("findByRoleName respects the page size")
    void respectsPageSize() {
        Page<User> firstPage = userRepository.findByRoleName(AppRole.ROLE_USER,
                PageRequest.of(0, 1, Sort.by("userId")));

        assertThat(firstPage.getContent()).hasSize(1);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isLast()).isFalse();
    }

    @Test
    @DisplayName("deleting a user leaves the role catalogue untouched")
    void deletingUserKeepsRoles() {
        long rolesBefore = roleRepository.count();

        userRepository.delete(userRepository.findByUserName("buyer2").orElseThrow());
        entityManager.flush();

        assertThat(userRepository.findByUserName("buyer2")).isEmpty();
        assertThat(roleRepository.count()).isEqualTo(rolesBefore);
    }
}
