package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.example.ohara.organization.domain.OrgRole;

import java.util.Set;

/** Adds an already-registered user. People without an account will be invited instead (invitations slice). */
public record AddMemberRequest(
    @NotBlank @Email String email,
    @NotEmpty Set<OrgRole> roles,
    @Size(max = 64) String externalRef
) {
}
