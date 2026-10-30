package org.example.ohara.enrollment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Renewal after another offline payment: replaces the live grant with one ending at {@code validUntil}. */
public record ExtendGrantRequest(@NotNull Instant validUntil, @NotBlank @Size(max = 128) String externalReference,
                                 @Size(max = 500) String note) {
}
