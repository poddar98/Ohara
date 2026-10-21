package org.example.ohara.organization.application;

import org.example.ohara.auth.application.AccountProvisioning;
import org.example.ohara.auth.application.IssuedTokens;
import org.example.ohara.auth.application.SecretTokens;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.organization.api.dto.AcceptInvitationRequest;
import org.example.ohara.organization.api.dto.InvitationPreview;
import org.example.ohara.organization.api.dto.InvitationResponse;
import org.example.ohara.organization.api.dto.InviteRequest;
import org.example.ohara.organization.domain.Cohort;
import org.example.ohara.organization.domain.Invitation;
import org.example.ohara.organization.domain.OrgRole;
import org.example.ohara.organization.domain.Organization;
import org.example.ohara.organization.domain.OrganizationMembership;
import org.example.ohara.organization.infrastructure.persistence.CohortRepository;
import org.example.ohara.organization.infrastructure.persistence.InvitationRepository;
import org.example.ohara.organization.infrastructure.persistence.OrganizationRepository;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Invitations to join an organization. Accepting never creates a second account for an email: an existing account
 * must sign in and accept; only an email without an account can sign up through the invitation.
 */
@Service
public class InvitationService {

    static final Duration INVITATION_TTL = Duration.ofDays(14);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final InvitationRepository invitations;
    private final OrganizationRepository organizations;
    private final CohortRepository cohorts;
    private final OrgAuthorizationService authorization;
    private final MembershipGrants grants;
    private final InvitationEmails emails;
    private final AccountProvisioning accounts;
    private final UserService userService;

    public InvitationService(InvitationRepository invitations, OrganizationRepository organizations, CohortRepository cohorts,
                             OrgAuthorizationService authorization, MembershipGrants grants, InvitationEmails emails,
                             AccountProvisioning accounts, UserService userService) {
        this.invitations = invitations;
        this.organizations = organizations;
        this.cohorts = cohorts;
        this.authorization = authorization;
        this.grants = grants;
        this.emails = emails;
        this.accounts = accounts;
        this.userService = userService;
    }

    public record InviteResult(InvitationResponse invitation, boolean created) {
    }

    // --- admin side --------------------------------------------------------------------------------

    @Transactional
    public InviteResult invite(CurrentActor actor, UUID organizationId, InviteRequest request) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_MEMBERS);
        Cohort cohort = request.cohortId() == null ? null : invitableCohort(organizationId, request.cohortId(), request.role());
        return send(organizationId, actor.userId(), request.email(), request.role(), cohort,
            blankToNull(request.firstName()), blankToNull(request.lastName()), blankToNull(request.externalRef()));
    }

    /**
     * Creates or re-issues the invitation and queues the email. Shared with bulk import (which has already authorized).
     * A pending invitation for the same email is re-used with a fresh token, so there is never more than one open link.
     */
    @Transactional
    InviteResult send(UUID organizationId, UUID invitedBy, String rawEmail, OrgRole role, Cohort cohort,
                      String firstName, String lastName, String externalRef) {
        String email = AccountProvisioning.normalizeEmail(rawEmail);
        Optional<User> account = accounts.findAccount(email);
        if (account.isPresent() && grants.holdsRole(organizationId, account.get().getId(), role)
            && (cohort == null || grants.isPlaced(cohort.getId(), account.get().getId()))) {
            throw new ApiException.Conflict("already_member", "This person is already a member with this role");
        }

        Instant now = Instant.now();
        String rawToken = SecretTokens.generate();
        String tokenHash = SecretTokens.sha256(rawToken);
        Instant expiresAt = now.plus(INVITATION_TTL);
        UUID cohortId = cohort == null ? null : cohort.getId();

        Optional<Invitation> pending = invitations.findPending(organizationId, email);
        Invitation invitation;
        boolean created;
        if (pending.isPresent()) {
            invitation = pending.get();
            invitation.reissue(tokenHash, expiresAt, role, cohortId, now);
            created = false;
        } else {
            invitation = invitations.save(new Invitation(organizationId, cohortId, email, role, firstName, lastName,
                externalRef, tokenHash, expiresAt, invitedBy));
            created = true;
        }
        emails.invitation(email, organization(organizationId).getName(), role, rawToken, tokenHash, expiresAt);
        return new InviteResult(InvitationResponse.of(invitation), created);
    }

    @Transactional(readOnly = true)
    public PageResponse<InvitationResponse> list(CurrentActor actor, UUID organizationId, Invitation.Status status, int page, int size) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_MEMBERS);
        return PageResponse.of(invitations.search(organizationId, status,
            PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size))), InvitationResponse::of);
    }

    /** New link, new expiry; the previous link stops working. Allowed for pending and expired invitations. */
    @Transactional
    public InvitationResponse resend(CurrentActor actor, UUID organizationId, UUID invitationId) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_MEMBERS);
        Invitation invitation = invitation(organizationId, invitationId);
        Invitation.Status status = invitation.effectiveStatus(Instant.now());
        if (status != Invitation.Status.PENDING && status != Invitation.Status.EXPIRED) {
            throw new ApiException.Conflict("invitation_not_resendable", "The invitation is " + status.name().toLowerCase());
        }
        if (invitation.getLastSentAt().isAfter(Instant.now().minus(RESEND_COOLDOWN))) {
            throw new ApiException.TooManyRequests("resend_too_soon", "The invitation was sent less than a minute ago");
        }
        if (status == Invitation.Status.EXPIRED && invitations.findPending(organizationId, invitation.getEmail())
                .filter(other -> !other.getId().equals(invitationId)).isPresent()) {
            throw new ApiException.Conflict("invitation_superseded", "A newer invitation exists for this email");
        }
        Instant now = Instant.now();
        String rawToken = SecretTokens.generate();
        String tokenHash = SecretTokens.sha256(rawToken);
        invitation.reissue(tokenHash, now.plus(INVITATION_TTL), invitation.getRole(), invitation.getCohortId(), now);
        emails.invitation(invitation.getEmail(), organization(organizationId).getName(), invitation.getRole(),
            rawToken, tokenHash, invitation.getExpiresAt());
        return InvitationResponse.of(invitation);
    }

    @Transactional
    public InvitationResponse revoke(CurrentActor actor, UUID organizationId, UUID invitationId) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_MEMBERS);
        Invitation invitation = invitation(organizationId, invitationId);
        if (invitation.getStatus() == Invitation.Status.ACCEPTED) {
            throw new ApiException.Conflict("invitation_already_accepted", "Accepted invitations can't be revoked; remove the member instead");
        }
        if (invitation.getStatus() != Invitation.Status.REVOKED) {
            invitation.revoke(Instant.now());
        }
        return InvitationResponse.of(invitation);
    }

    // --- invitee side ------------------------------------------------------------------------------

    @Transactional
    public InvitationPreview preview(String rawToken) {
        Invitation invitation = usable(rawToken);
        Organization organization = organization(invitation.getOrganizationId());
        String cohortName = invitation.getCohortId() == null ? null
            : cohorts.findById(invitation.getCohortId()).map(Cohort::getName).orElse(null);
        return new InvitationPreview(organization.getName(), invitation.getRole(), cohortName, invitation.getEmail(),
            invitation.getFirstName(), invitation.getLastName(), accounts.findAccount(invitation.getEmail()).isPresent(),
            invitation.getExpiresAt());
    }

    public record AcceptResult(UUID organizationId, OrganizationMembership membership, UUID cohortId,
                               boolean placedInCohort, IssuedTokens session) {
    }

    /**
     * Signed-in caller: their account's email must match the invitation. Anonymous caller: allowed only when no account
     * exists for the email, in which case one is created (already verified, since the link proves the inbox).
     */
    @Transactional
    public AcceptResult accept(String rawToken, CurrentActor actor, AcceptInvitationRequest request) {
        Invitation invitation = findByToken(rawToken);
        if (invitation.getStatus() == Invitation.Status.ACCEPTED && actor != null
            && actor.userId().equals(invitation.getAcceptedByUserId())) {
            // Repeated click by the same person: report the current state instead of failing.
            OrganizationMembership membership = grants.membership(invitation.getOrganizationId(), actor.userId())
                .orElseThrow(() -> new ApiException.Gone("invitation_already_used", "This invitation was already accepted"));
            boolean placed = invitation.getCohortId() != null && grants.isPlaced(invitation.getCohortId(), actor.userId());
            return new AcceptResult(invitation.getOrganizationId(), membership, invitation.getCohortId(), placed, null);
        }
        requireUsable(invitation);
        Organization organization = organization(invitation.getOrganizationId());
        if (!organization.isActive()) {
            throw new ApiException.Forbidden("organization_inactive", "This organization is not accepting members");
        }

        User user;
        IssuedTokens session = null;
        if (actor != null) {
            user = userService.findById(actor.userId()).orElseThrow(() ->
                new ApiException.Unauthenticated("invalid_session", "Session is no longer valid"));
            if (!user.getEmail().equalsIgnoreCase(invitation.getEmail())) {
                throw new ApiException.Conflict("invitation_email_mismatch",
                    "This invitation was sent to a different email address; sign in with that address");
            }
            if (!user.canSignIn()) {
                throw new ApiException.Forbidden("account_inactive", "Account is not active");
            }
            if (!user.isEmailVerified()) {
                accounts.markEmailVerified(user);
                session = accounts.startSession(user); // the old session was limited to unverified scope
            }
        } else {
            if (accounts.findAccount(invitation.getEmail()).isPresent()) {
                throw new ApiException.Conflict("account_exists", "An account exists for this email; sign in to accept");
            }
            user = accounts.createVerifiedAccount(invitation.getEmail(),
                required(request == null ? null : request.firstName(), invitation.getFirstName(), "firstName"),
                required(request == null ? null : request.lastName(), invitation.getLastName(), "lastName"),
                required(request == null ? null : request.password(), null, "password"));
            session = accounts.startSession(user);
        }

        OrganizationMembership membership = grants.grant(invitation.getOrganizationId(), user, invitation.getRole(),
            invitation.getExternalRef());
        boolean placed = false;
        if (invitation.getCohortId() != null) {
            placed = cohorts.findById(invitation.getCohortId()).map(c -> grants.place(c, user)).orElse(false);
        }
        invitation.accept(user.getId(), Instant.now());
        return new AcceptResult(invitation.getOrganizationId(), membership, invitation.getCohortId(), placed, session);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private Invitation findByToken(String rawToken) {
        return invitations.findByTokenHash(SecretTokens.sha256(rawToken))
            .orElseThrow(() -> new ApiException.NotFound("invitation_not_found", "This invitation link is not valid"));
    }

    private Invitation usable(String rawToken) {
        Invitation invitation = findByToken(rawToken);
        requireUsable(invitation);
        return invitation;
    }

    private void requireUsable(Invitation invitation) {
        Instant now = Instant.now();
        invitation.expireIfDue(now);
        switch (invitation.getStatus()) {
            case PENDING -> { }
            case EXPIRED -> throw new ApiException.Gone("invitation_expired", "This invitation has expired; ask for a new one");
            case REVOKED -> throw new ApiException.Gone("invitation_revoked", "This invitation was withdrawn");
            case ACCEPTED -> throw new ApiException.Gone("invitation_already_used", "This invitation was already accepted");
        }
    }

    private Cohort invitableCohort(UUID organizationId, UUID cohortId, OrgRole role) {
        if (role != OrgRole.LEARNER) {
            throw new ApiException.BadRequest("cohort_requires_learner", "Only learners can be invited into a cohort");
        }
        Cohort cohort = cohorts.findByIdAndOrganizationId(cohortId, organizationId)
            .orElseThrow(() -> new ApiException.NotFound("cohort_not_found", "Cohort not found"));
        if (!cohort.acceptsMembers()) {
            throw new ApiException.Conflict("cohort_closed", "The cohort is " + cohort.getStatus().name().toLowerCase());
        }
        return cohort;
    }

    private Invitation invitation(UUID organizationId, UUID invitationId) {
        return invitations.findByIdAndOrganizationId(invitationId, organizationId)
            .orElseThrow(() -> new ApiException.NotFound("invitation_not_found", "Invitation not found"));
    }

    private Organization organization(UUID organizationId) {
        return organizations.findById(organizationId).orElseThrow(OrgAuthorizationService::notFound);
    }

    private static String required(String provided, String fallback, String field) {
        String value = blankToNull(provided) != null ? provided.trim() : fallback;
        if (value == null) {
            throw new ApiException.BadRequest("missing_" + field, field + " is required to create your account");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
