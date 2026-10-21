package org.example.ohara.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @NotBlank(message = "Token is required")
    @Size(max = 128, message = "Token is invalid")
    String token,

    // BCrypt only uses the first 72 bytes.
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    String newPassword
) {
}
