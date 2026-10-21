package org.example.ohara.organization.api;

import jakarta.validation.Valid;
import org.example.ohara.auth.application.RefreshCookies;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.organization.api.dto.AcceptInvitationRequest;
import org.example.ohara.organization.api.dto.AcceptInvitationResponse;
import org.example.ohara.organization.api.dto.InvitationPreview;
import org.example.ohara.organization.application.InvitationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.TreeSet;

/** Invitee-facing endpoints. Public: the emailed token is the credential; a signed-in session is used when present. */
@RestController
@RequestMapping("/api/invitations/{token}")
public class PublicInvitationController {

    private final InvitationService invitations;
    private final RefreshCookies refreshCookies;

    public PublicInvitationController(InvitationService invitations, RefreshCookies refreshCookies) {
        this.invitations = invitations;
        this.refreshCookies = refreshCookies;
    }

    @GetMapping
    public InvitationPreview preview(@PathVariable String token) {
        return invitations.preview(token);
    }

    @PostMapping("/accept")
    public ResponseEntity<AcceptInvitationResponse> accept(@PathVariable String token,
                                                           @AuthenticationPrincipal CurrentActor actor,
                                                           @Valid @RequestBody(required = false) AcceptInvitationRequest request) {
        InvitationService.AcceptResult result = invitations.accept(token, actor, request);
        AcceptInvitationResponse body = new AcceptInvitationResponse(result.organizationId(),
            new TreeSet<>(result.membership().getRoles()), result.cohortId(), result.placedInCohort(),
            result.session() == null ? null : result.session().accessToken(),
            result.session() == null ? null : "Bearer");
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.session() != null) {
            response.header(HttpHeaders.SET_COOKIE, refreshCookies.issue(result.session()).toString());
        }
        return response.body(body);
    }
}
