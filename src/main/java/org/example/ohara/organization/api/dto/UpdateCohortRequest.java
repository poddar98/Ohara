package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.Size;
import org.example.ohara.organization.domain.Cohort;

import java.time.LocalDate;

/** Any field left null is unchanged. Completing or archiving a cohort closes its open placements as COMPLETED. */
public record UpdateCohortRequest(
    @Size(min = 1, max = 200) String name,
    LocalDate startsOn,
    LocalDate endsOn,
    Cohort.Status status
) {
}
