package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TransferCohortMemberRequest(@NotNull UUID toCohortId) {
}
