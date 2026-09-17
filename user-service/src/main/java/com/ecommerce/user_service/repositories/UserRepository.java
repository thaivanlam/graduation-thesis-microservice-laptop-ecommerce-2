package com.ecommerce.user_service.repositories;


import com.ecommerce.user_service.model.AppRole;
import com.ecommerce.user_service.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;


@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUserName(String userame);

    /**
     * The lookup the platform actually keys on since ADR-0012: email is what the access
     * token carries and what Order, Cart and ProductSnapshot all reference.
     */
    Optional<User> findByEmail(String email);


    boolean existsByEmail(String email);

    boolean existsByUserName(String user1);

    @Query("SELECT u FROM User u JOIN u.roles r WHERE r.roleName = :role")
    Page<User> findByRoleName(@Param("role") AppRole role, Pageable pageable);
}
