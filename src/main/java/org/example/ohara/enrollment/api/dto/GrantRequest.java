package org.example.ohara.enrollment.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Contract team: access after an offline payment. {@code externalReference} is the contract number or bank UTR.
 * Names are needed only when the person has no account yet. {@code validUntil} defaults to the offering's duration.
 */
public record GrantRequest(@NotBlank @Email @Size(max = 320) String email, @Size(max = 100) String firstName,
                           @Size(max = 100) String lastName, @NotBlank @Size(max = 128) String externalReference,
                           Instant validUntil, @Size(max = 500) String note) {
}
