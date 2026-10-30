package org.example.ohara.enrollment.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** List price is display only — payment happens offline. */
public record CreateB2COfferingRequest(@NotNull UUID courseId, @Size(max = 200) String title,
                                       @Positive Integer accessDurationDays, @PositiveOrZero Long listPriceMinor,
                                       @Pattern(regexp = "^[A-Z]{3}$") String currency) {
}
