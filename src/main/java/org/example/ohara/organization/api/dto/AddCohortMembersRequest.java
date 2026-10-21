package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record AddCohortMembersRequest(@NotEmpty @Size(max = 500) Set<UUID> userIds) {
}
