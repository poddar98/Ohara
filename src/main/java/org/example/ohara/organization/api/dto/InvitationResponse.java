package org.example.ohara.organization.api.dto;

import org.example.ohara.organization.domain.Invitation;
import org.example.ohara.organization.domain.OrgRole;

import java.time.Instant;
import java.util.UUID;

public record InvitationResponse(UUID id, String email, OrgRole role, UUID cohortId, Invitation.Status status,
                                 Instant expiresAt, int sendCount, Instant lastSentAt, Instant acceptedAt, Instant createdAt) {

    public static InvitationResponse of(Invitation i) {
        return new InvitationResponse(i.getId(), i.getEmail(), i.getRole(), i.getCohortId(), i.effectiveStatus(Instant.now()),
            i.getExpiresAt(), i.getSendCount(), i.getLastSentAt(), i.getAcceptedAt(), i.getCreatedAt());
    }
}
