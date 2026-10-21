package org.example.ohara.organization.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.organization.api.dto.ChangeOrganizationStatusRequest;
import org.example.ohara.organization.api.dto.CreateOrganizationRequest;
import org.example.ohara.organization.api.dto.OrganizationResponse;
import org.example.ohara.organization.application.OrganizationService;
import org.example.ohara.organization.domain.Organization;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Platform-level organization management (platform admins; support may list). */
@RestController
@RequestMapping("/api/admin/organizations")
public class AdminOrganizationController {

    private final OrganizationService organizations;

    public AdminOrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationResponse create(@AuthenticationPrincipal CurrentActor actor,
                                       @Valid @RequestBody CreateOrganizationRequest request) {
        return organizations.create(actor, request);
    }

    @GetMapping
    public PageResponse<OrganizationResponse> list(@AuthenticationPrincipal CurrentActor actor,
                                                   @RequestParam(required = false) Organization.Status status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return organizations.listAll(actor, status, page, size);
    }

    @PutMapping("/{orgId}/status")
    public OrganizationResponse changeStatus(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                             @Valid @RequestBody ChangeOrganizationStatusRequest request) {
        return organizations.changeStatus(actor, orgId, request.status());
    }
}
