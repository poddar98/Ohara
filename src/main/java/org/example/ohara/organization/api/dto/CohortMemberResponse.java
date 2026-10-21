package org.example.ohara.organization.api.dto;

import org.example.ohara.organization.domain.CohortMembership;

import java.time.Instant;
import java.util.UUID;

public record CohortMemberResponse(UUID userId, String email, String name, CohortMembership.Status status,
                                   Instant joinedAt, Instant leftAt) {

    public static CohortMemberResponse of(CohortMembership cm) {
        return new CohortMemberResponse(cm.getUser().getId(), cm.getUser().getEmail(),
            cm.getUser().getFirstName() + " " + cm.getUser().getLastName(), cm.getStatus(), cm.getJoinedAt(), cm.getLeftAt());
    }
}
