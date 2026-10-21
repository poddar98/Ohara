package org.example.ohara.organization.api.dto;

import org.example.ohara.organization.domain.OrgRole;

import java.util.Set;
import java.util.UUID;

/** {@code accessToken} is present when acceptance created or activated the account (a refresh cookie is set too). */
public record AcceptInvitationResponse(UUID organizationId, Set<OrgRole> roles, UUID cohortId, boolean placedInCohort,
                                       String accessToken, String tokenType) {
}
