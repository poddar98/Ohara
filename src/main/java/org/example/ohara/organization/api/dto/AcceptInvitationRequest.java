package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.Size;

/** Required only when accepting without an account; ignored when the caller is signed in. */
public record AcceptInvitationRequest(
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters") String password
) {
}
