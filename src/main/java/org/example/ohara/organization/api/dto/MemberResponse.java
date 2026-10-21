package org.example.ohara.organization.api.dto;

import org.example.ohara.organization.domain.OrgRole;
import org.example.ohara.organization.domain.OrganizationMembership;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public record MemberResponse(UUID userId, String email, String name, Set<OrgRole> roles,
                             OrganizationMembership.Status status, String externalRef, Instant joinedAt) {

    public static MemberResponse of(OrganizationMembership m) {
        return new MemberResponse(m.getUser().getId(), m.getUser().getEmail(),
            m.getUser().getFirstName() + " " + m.getUser().getLastName(),
            new TreeSet<>(m.getRoles()), m.getStatus(), m.getExternalRef(), m.getJoinedAt());
    }
}
