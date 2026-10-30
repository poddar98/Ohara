package org.example.ohara.enrollment.api.dto;

import org.example.ohara.enrollment.domain.AccessGrant;

import java.time.Instant;
import java.util.UUID;

/** One line of the reconciliation export finance matches against bank records. */
public record GrantReportRow(UUID grantId, Instant createdAt, AccessGrant.Source source, String externalReference,
                             String learnerEmail, String courseTitle, UUID offeringId, Instant validFrom, Instant validUntil,
                             String grantedBy, Instant revokedAt, AccessGrant.RevokedReason revokedReason, String revokedBy,
                             String note) {
}
