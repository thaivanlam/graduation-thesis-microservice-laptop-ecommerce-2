package com.ecommerce.user_service.payload;

import java.util.HashSet;
import java.util.Set;


import com.ecommerce.user_service.model.Role;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {
    private Long userId;
    private String username;
    private String email;
    // No password field since ADR-0012. The admin seller and customer lists used to map
    // User straight onto this DTO, so they returned every account's BCrypt hash. There is
    // no hash to return now, and no field for one to reappear in.
    private Set<Role> roles = new HashSet<>();

}