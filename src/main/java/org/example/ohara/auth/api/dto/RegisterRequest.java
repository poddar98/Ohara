package org.example.ohara.auth.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "First name is required")
    String firstName,

    @NotBlank(message = "Last name is required")
    String lastName,

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    String email,

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    String password,

    @NotNull(message = "You must accept the terms of use and privacy notice")
    @AssertTrue(message = "You must accept the terms of use and privacy notice")
    Boolean acceptTerms,

    /** Optional opt-in; absent means no. */
    Boolean marketingEmails
) {
}
