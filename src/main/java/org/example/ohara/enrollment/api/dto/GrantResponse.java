package org.example.ohara.enrollment.api.dto;

import org.example.ohara.enrollment.domain.AccessGrant;

import java.time.Instant;
import java.util.UUID;

/** {@code created} false = the same grant already existed (repeat submission); {@code userCreated} = a new account was made. */
public record GrantResponse(UUID grantId, UUID enrollmentId, UUID userId, String email, AccessGrant.Source source,
                            String externalReference, Instant validFrom, Instant validUntil, Instant revokedAt,
                            AccessGrant.RevokedReason revokedReason, boolean created, boolean userCreated) {

    public static GrantResponse of(AccessGrant g, String email, boolean created, boolean userCreated) {
        return new GrantResponse(g.getId(), g.getEnrollmentId(), g.getUserId(), email, g.getSource(), g.getExternalReference(),
            g.getValidFrom(), g.getValidUntil(), g.getRevokedAt(), g.getRevokedReason(), created, userCreated);
    }
}
