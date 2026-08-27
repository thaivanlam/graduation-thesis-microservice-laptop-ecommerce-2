package com.ecommerce.user_service.service;

import com.ecommerce.user_service.exceptions.APIException;
import com.ecommerce.user_service.exceptions.ResourceNotFoundException;
import com.ecommerce.user_service.model.AppRole;
import com.ecommerce.user_service.model.Role;
import com.ecommerce.user_service.model.User;
import com.ecommerce.user_service.payload.AuthenticationResult;
import com.ecommerce.user_service.payload.UserDTO;
import com.ecommerce.user_service.payload.UserResponse;
import com.ecommerce.user_service.repositories.RoleRepository;
import com.ecommerce.user_service.repositories.UserRepository;
import com.ecommerce.user_service.security.jwt.JwtUtils;
import com.ecommerce.user_service.security.request.ChangePasswordRequest;
import com.ecommerce.user_service.security.request.LoginRequest;
import com.ecommerce.user_service.security.request.SignupRequest;
import com.ecommerce.user_service.security.request.VerifyPasswordRequest;
import com.ecommerce.user_service.security.response.MessageResponse;
import com.ecommerce.user_service.security.response.UserInfoResponse;
import com.ecommerce.user_service.util.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class AuthServiceImpl implements AuthService {
    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ModelMapper modelMapper;

    @Autowired
    private NotificationProducer notificationProducer;

    @Autowired
    private AuthUtil authUtil;


    @Override
    public AuthenticationResult login(LoginRequest loginRequest) {
        Optional<User> optionalUser = userRepository.findByUserName(loginRequest.getUsername());

        if (optionalUser.isEmpty() || !passwordEncoder.matches(loginRequest.getPassword(), optionalUser.get().getPassword())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bad credentials");
        }

        User user = optionalUser.get();
        String token = jwtUtils.generateToken(user);
        ResponseCookie jwtCookie = jwtUtils.generateJwtCookie(token);
        List<String> roles = user.getRoles().stream().map(role -> role.getRoleName().name()).collect(Collectors.toList());
        UserInfoResponse loginResponse = new UserInfoResponse(user.getUserId(), token, user.getUserName(), user.getEmail(), roles);
        return new AuthenticationResult(loginResponse, jwtCookie);
    }

    @Override
    public ResponseEntity<MessageResponse> register(SignupRequest signupRequest) {
        // Validate username
        if (userRepository.existsByUserName(signupRequest.getUsername())) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("Error: Username is already taken!"));
        }

        // Validate email
        if (userRepository.existsByEmail(signupRequest.getEmail())) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("Error: Email is already taken!"));
        }

        // Create new user
        User user = new User(
                signupRequest.getUsername(),
                signupRequest.getEmail(),
                passwordEncoder.encode(signupRequest.getPassword())
        );

        Set<String> strRoles = signupRequest.getRoles();
        Set<Role> roles = new HashSet<>();

        // If no roles specified, default to USER
        if (strRoles == null || strRoles.isEmpty()) {
            Role userRole = roleRepository.findByRoleName(AppRole.ROLE_USER)
                    .orElseThrow(() -> new RuntimeException("Error: Role is not found!"));
            roles.add(userRole);
        } else {
            // Process each requested role
            for (String role : strRoles) {
                switch (role.toLowerCase()) {
                    case "admin":
                        Role adminRole = roleRepository.findByRoleName(AppRole.ROLE_ADMIN)
                                .orElseThrow(() -> new RuntimeException("Error: Admin role is not found"));
                        roles.add(adminRole);
                        break;

                    case "seller":
                        Role sellerRole = roleRepository.findByRoleName(AppRole.ROLE_SELLER)
                                .orElseThrow(() -> new RuntimeException("Error: Seller role is not found"));
                        roles.add(sellerRole);
                        break;

                    case "user":
                    default:
                        Role userRole = roleRepository.findByRoleName(AppRole.ROLE_USER)
                                .orElseThrow(() -> new RuntimeException("Error: User role is not found"));
                        roles.add(userRole);
                        break;
                }
            }
        }

        user.setRoles(roles);
        userRepository.save(user);

        // Send welcome email
        notificationProducer.sendRegistrationEmail(user.getEmail(), user.getUserName());

        return ResponseEntity.ok(new MessageResponse("User registered successfully"));
    }

    @Override
    public UserInfoResponse getCurrentUserDetails(HttpServletRequest httpServletRequest) {
        String jwt = jwtUtils.getJwtFromCookies(httpServletRequest);
        if (jwt == null || !jwtUtils.validateJwtToken(jwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing authentication token");
        }
        String username = jwtUtils.getUserNameFromJwtToken(jwt);
        User user = userRepository.findByUserName(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        List<String> roles = user.getRoles().stream()
                .map(role -> role.getRoleName().name())
                .collect(Collectors.toList());
        return new UserInfoResponse(user.getUserId(), user.getUserName(), roles);
    }

    @Override
    public ResponseCookie logoutUser() {
        return jwtUtils.getCleanJwtCookie();
    }

    @Override
    public String getUsername(HttpServletRequest httpServletRequest) {
        String jwt = jwtUtils.getJwtFromCookies(httpServletRequest);
        if (jwt == null || !jwtUtils.validateJwtToken(jwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing authentication token");
        }
        String username = jwtUtils.getUserNameFromJwtToken(jwt);
        return username;
    }

    @Override
    public UserResponse getAllSellers(Pageable pageable) {
        Page<User> allUsers = userRepository.findByRoleName(AppRole.ROLE_SELLER, pageable);
        List<UserDTO> userDtos = allUsers.getContent()
                .stream()
                .map(p -> modelMapper.map(p, UserDTO.class))
                .collect(Collectors.toList());

        UserResponse response = new UserResponse();
        response.setContent(userDtos);
        response.setPageNumber(allUsers.getNumber());
        response.setPageSize(allUsers.getSize());
        response.setTotalElements(allUsers.getTotalElements());
        response.setTotalPages(allUsers.getTotalPages());
        response.setLastPage(allUsers.isLast());
        return response;
    }

    @Override
    public UserResponse getAllCustomers(Pageable pageable) {
        Page<User> allUsers = userRepository.findByRoleName(AppRole.ROLE_USER, pageable);
        List<UserDTO> userDtos = allUsers.getContent()
                .stream()
                .map(p -> modelMapper.map(p, UserDTO.class))
                .collect(Collectors.toList());

        UserResponse response = new UserResponse();
        response.setContent(userDtos);
        response.setPageNumber(allUsers.getNumber());
        response.setPageSize(allUsers.getSize());
        response.setTotalElements(allUsers.getTotalElements());
        response.setTotalPages(allUsers.getTotalPages());
        response.setLastPage(allUsers.isLast());
        return response;
    }

    @Override
    public MessageResponse deleteCustomer(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

        // Check if user is actually a customer (only has ROLE_USER)
        boolean isOnlyCustomer = user.getRoles().stream()
                .allMatch(role -> role.getRoleName() == AppRole.ROLE_USER);

        if (!isOnlyCustomer) {
            throw new APIException("Cannot delete user: Not a customer or has additional roles");
        }

        userRepository.delete(user);
        return new MessageResponse("Customer deleted successfully");
    }

    @Override
    public MessageResponse deleteSeller(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

        // Check if user has seller role
        boolean isSeller = user.getRoles().stream()
                .anyMatch(role -> role.getRoleName() == AppRole.ROLE_SELLER);

        if (!isSeller) {
            throw new APIException("Cannot delete user: Not a seller");
        }

        // Don't allow deleting admin users
        boolean isAdmin = user.getRoles().stream()
                .anyMatch(role -> role.getRoleName() == AppRole.ROLE_ADMIN);

        if (isAdmin) {
            throw new APIException("Cannot delete user: User has admin role");
        }

        userRepository.delete(user);
        return new MessageResponse("Seller deleted successfully");
    }

    @Override
    public MessageResponse verifyCurrentPassword(VerifyPasswordRequest verifyPasswordRequest) {
        User user = authUtil.loggedInUser();

        if (!passwordEncoder.matches(verifyPasswordRequest.getCurrentPassword(), user.getPassword())) {
            throw new APIException("Current password is incorrect");
        }

        return new MessageResponse("Password verified");
    }

    @Override
    public MessageResponse changePassword(ChangePasswordRequest changePasswordRequest) {
        User user = authUtil.loggedInUser();

        if (!passwordEncoder.matches(changePasswordRequest.getCurrentPassword(), user.getPassword())) {
            throw new APIException("Current password is incorrect");
        }

        if (!changePasswordRequest.getNewPassword().equals(changePasswordRequest.getConfirmPassword())) {
            throw new APIException("New password and confirmation do not match");
        }

        if (passwordEncoder.matches(changePasswordRequest.getNewPassword(), user.getPassword())) {
            throw new APIException("New password must be different from the current password");
        }

        user.setPassword(passwordEncoder.encode(changePasswordRequest.getNewPassword()));
        userRepository.save(user);

        notificationProducer.sendPasswordChangedEmail(user.getEmail(), user.getUserName());

        return new MessageResponse("Password changed successfully");
    }
}
