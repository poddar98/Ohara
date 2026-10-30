package org.example.ohara.enrollment.api.dto;

import java.time.Instant;

/** Moves the access window; existing cohort grants follow. Null fields are unchanged. */
public record UpdateOfferingRequest(Instant startsAt, Instant endsAt) {
}
