package org.example.ohara.organization.api.dto;

import org.example.ohara.organization.domain.OrgRole;

import java.time.Instant;

/**
 * What the invitee sees before accepting. {@code accountExists} tells the UI whether to show sign-in or sign-up;
 * it's only revealed to the holder of the emailed link.
 */
public record InvitationPreview(String organizationName, OrgRole role, String cohortName, String email,
                                String firstName, String lastName, boolean accountExists, Instant expiresAt) {
}
