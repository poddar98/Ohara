package org.example.ohara.enrollment.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.example.ohara.enrollment.domain.AccessGrant;

/** REFUNDED_OFFLINE, CONTRACT_ENDED or ADMIN. */
public record RevokeGrantRequest(@NotNull AccessGrant.RevokedReason reason, @Size(max = 500) String note) {
}
