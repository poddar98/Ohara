package org.example.ohara.organization.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.organization.api.dto.AddMemberRequest;
import org.example.ohara.organization.api.dto.MemberResponse;
import org.example.ohara.organization.api.dto.MyOrganizationResponse;
import org.example.ohara.organization.api.dto.OrganizationResponse;
import org.example.ohara.organization.api.dto.UpdateMemberRequest;
import org.example.ohara.organization.api.dto.UpdateOrganizationRequest;
import org.example.ohara.organization.application.OrganizationService;
import org.example.ohara.organization.domain.OrgRole;
import org.example.ohara.organization.domain.OrganizationMembership;
import org.example.ohara.platform.audit.AuditQueries;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class OrganizationController {

    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @GetMapping("/api/users/me/organizations")
    public List<MyOrganizationResponse> mine(@AuthenticationPrincipal CurrentActor actor) {
        return organizations.myOrganizations(actor);
    }

    @GetMapping("/api/orgs/{orgId}/audit")
    public PageResponse<AuditQueries.Event> audit(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                                  @RequestParam(required = false) UUID actorUserId,
                                                  @RequestParam(required = false) String action,
                                                  @RequestParam(required = false) String resourceType,
                                                  @RequestParam(required = false) String resourceId,
                                                  @RequestParam(required = false) Instant from,
                                                  @RequestParam(required = false) Instant to,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return organizations.audit(actor, orgId, actorUserId, action, resourceType, resourceId, from, to, page, size);
    }

    @GetMapping("/api/orgs/{orgId}")
    public OrganizationResponse get(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId) {
        return organizations.get(actor, orgId);
    }

    @PatchMapping("/api/orgs/{orgId}")
    public OrganizationResponse update(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                       @Valid @RequestBody UpdateOrganizationRequest request) {
        return organizations.rename(actor, orgId, request.name());
    }

    @GetMapping("/api/orgs/{orgId}/members")
    public PageResponse<MemberResponse> members(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                                @RequestParam(required = false) OrgRole role,
                                                @RequestParam(required = false) OrganizationMembership.Status status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return organizations.listMembers(actor, orgId, role, status, page, size);
    }

    /** 201 when a new member was added, 200 when an existing member gained roles or was re-activated. */
    @PostMapping("/api/orgs/{orgId}/members")
    public ResponseEntity<MemberResponse> addMember(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                                    @Valid @RequestBody AddMemberRequest request) {
        OrganizationService.AddMemberResult result = organizations.addMember(actor, orgId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.member());
    }

    @PatchMapping("/api/orgs/{orgId}/members/{userId}")
    public MemberResponse updateMember(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                       @PathVariable UUID userId, @Valid @RequestBody UpdateMemberRequest request) {
        return organizations.updateMember(actor, orgId, userId, request);
    }
}
