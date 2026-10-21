package org.example.ohara.organization.api.dto;

import org.example.ohara.organization.domain.Cohort;

import java.time.LocalDate;
import java.util.UUID;

public record CohortResponse(UUID id, String name, String code, LocalDate startsOn, LocalDate endsOn,
                             Cohort.Status status, long activeMembers) {

    public static CohortResponse of(Cohort cohort, long activeMembers) {
        return new CohortResponse(cohort.getId(), cohort.getName(), cohort.getCode(), cohort.getStartsOn(),
            cohort.getEndsOn(), cohort.getStatus(), activeMembers);
    }
}
