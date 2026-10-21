package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.ohara.organization.domain.Organization;

public record CreateOrganizationRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$",
        message = "Slug must be 3-64 lowercase letters, digits or hyphens, not starting or ending with a hyphen")
    String slug,
    @NotNull Organization.Type type
) {
}
