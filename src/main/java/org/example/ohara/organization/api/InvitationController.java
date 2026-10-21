package org.example.ohara.organization.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.organization.api.dto.InvitationResponse;
import org.example.ohara.organization.api.dto.InviteRequest;
import org.example.ohara.organization.application.InvitationService;
import org.example.ohara.organization.domain.Invitation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orgs/{orgId}/invitations")
public class InvitationController {

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    /** 201 for a new invitation; 200 when an open invitation for the email was re-issued with a fresh link. */
    @PostMapping
    public ResponseEntity<InvitationResponse> invite(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                                     @Valid @RequestBody InviteRequest request) {
        InvitationService.InviteResult result = invitations.invite(actor, orgId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.invitation());
    }

    @GetMapping
    public PageResponse<InvitationResponse> list(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                                 @RequestParam(required = false) Invitation.Status status,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        return invitations.list(actor, orgId, status, page, size);
    }

    @PostMapping("/{invitationId}/resend")
    public InvitationResponse resend(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                     @PathVariable UUID invitationId) {
        return invitations.resend(actor, orgId, invitationId);
    }

    @PostMapping("/{invitationId}/revoke")
    public InvitationResponse revoke(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                     @PathVariable UUID invitationId) {
        return invitations.revoke(actor, orgId, invitationId);
    }
}
