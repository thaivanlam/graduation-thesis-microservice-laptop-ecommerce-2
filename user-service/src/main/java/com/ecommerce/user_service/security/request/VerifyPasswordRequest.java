package com.ecommerce.user_service.security.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerifyPasswordRequest {
    @NotBlank
    private String currentPassword;
}
