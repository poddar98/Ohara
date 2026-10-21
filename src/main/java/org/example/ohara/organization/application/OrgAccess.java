package org.example.ohara.organization.application;

import org.example.ohara.organization.domain.OrgRole;

import java.util.Set;
import java.util.UUID;

/** Result of a successful authorization: who acted, in which organization, with which org roles (empty for platform staff). */
public record OrgAccess(UUID organizationId, UUID actorUserId, Set<OrgRole> roles, boolean platformStaff) {
}
