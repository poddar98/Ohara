package org.example.ohara.enrollment.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.enrollment.api.dto.CreateB2BOfferingRequest;
import org.example.ohara.enrollment.api.dto.CreateB2COfferingRequest;
import org.example.ohara.enrollment.api.dto.OfferingEnrollmentRow;
import org.example.ohara.enrollment.api.dto.OfferingResponse;
import org.example.ohara.enrollment.api.dto.UpdateOfferingRequest;
import org.example.ohara.enrollment.application.OfferingService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class OfferingController {

    private final OfferingService offerings;

    public OfferingController(OfferingService offerings) {
        this.offerings = offerings;
    }

    // --- organization (B2B) ---

    @PostMapping("/api/orgs/{orgId}/offerings")
    @ResponseStatus(HttpStatus.CREATED)
    public OfferingResponse createB2B(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                      @Valid @RequestBody CreateB2BOfferingRequest request) {
        return offerings.createB2B(actor, orgId, request);
    }

    @GetMapping("/api/orgs/{orgId}/offerings")
    public List<OfferingResponse> listB2B(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId) {
        return offerings.listForOrganization(actor, orgId);
    }

    @GetMapping("/api/orgs/{orgId}/offerings/{offeringId}")
    public OfferingResponse getB2B(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId, @PathVariable UUID offeringId) {
        return offerings.getForOrganization(actor, orgId, offeringId);
    }

    @PatchMapping("/api/orgs/{orgId}/offerings/{offeringId}")
    public OfferingResponse reschedule(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                       @PathVariable UUID offeringId, @Valid @RequestBody UpdateOfferingRequest request) {
        return offerings.reschedule(actor, orgId, offeringId, request);
    }

    @PostMapping("/api/orgs/{orgId}/offerings/{offeringId}/open")
    public OfferingResponse openB2B(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId, @PathVariable UUID offeringId) {
        return offerings.openForOrganization(actor, orgId, offeringId);
    }

    @PostMapping("/api/orgs/{orgId}/offerings/{offeringId}/close")
    public OfferingResponse closeB2B(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId, @PathVariable UUID offeringId) {
        return offerings.closeForOrganization(actor, orgId, offeringId);
    }

    @GetMapping("/api/orgs/{orgId}/offerings/{offeringId}/enrollments")
    public List<OfferingEnrollmentRow> enrollmentsB2B(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                                      @PathVariable UUID offeringId) {
        return offerings.enrollmentsForOrganization(actor, orgId, offeringId);
    }

    @PostMapping("/api/orgs/{orgId}/enrollments/{enrollmentId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeB2B(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId, @PathVariable UUID enrollmentId) {
        offerings.revokeForOrganization(actor, orgId, enrollmentId);
    }

    // --- platform (B2C) ---

    @PostMapping("/api/admin/offerings")
    @ResponseStatus(HttpStatus.CREATED)
    public OfferingResponse createB2C(@AuthenticationPrincipal CurrentActor actor, @Valid @RequestBody CreateB2COfferingRequest request) {
        return offerings.createB2C(actor, request);
    }

    @GetMapping("/api/admin/offerings")
    public List<OfferingResponse> listB2C(@AuthenticationPrincipal CurrentActor actor) {
        return offerings.listB2C(actor);
    }

    @PostMapping("/api/admin/offerings/{offeringId}/open")
    public OfferingResponse openB2C(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID offeringId) {
        return offerings.openB2C(actor, offeringId);
    }

    @PostMapping("/api/admin/offerings/{offeringId}/close")
    public OfferingResponse closeB2C(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID offeringId) {
        return offerings.closeB2C(actor, offeringId);
    }

    @GetMapping("/api/admin/offerings/{offeringId}/enrollments")
    public List<OfferingEnrollmentRow> enrollmentsB2C(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID offeringId) {
        return offerings.enrollmentsB2C(actor, offeringId);
    }
}
