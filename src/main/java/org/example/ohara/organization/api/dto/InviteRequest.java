package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.example.ohara.organization.domain.OrgRole;

import java.util.UUID;

/** {@code cohortId} places the person in that cohort on acceptance (LEARNER only). Names prefill the sign-up form. */
public record InviteRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotNull OrgRole role,
    UUID cohortId,
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    @Size(max = 64) String externalRef
) {
}
