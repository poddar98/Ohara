package org.example.ohara.organization.api.dto;

import java.util.List;
import java.util.UUID;

/** {@code notEligible}: not an active LEARNER member of the organization. */
public record AddCohortMembersResponse(List<UUID> added, List<UUID> alreadyMember, List<UUID> notEligible) {
}
