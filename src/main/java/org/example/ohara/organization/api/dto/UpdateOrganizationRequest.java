package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateOrganizationRequest(@NotBlank @Size(max = 200) String name) {
}
