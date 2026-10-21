package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.NotNull;
import org.example.ohara.organization.domain.Organization;

public record ChangeOrganizationStatusRequest(@NotNull Organization.Status status) {
}
