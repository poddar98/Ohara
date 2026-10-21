package org.example.ohara.organization.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.organization.api.dto.AddCohortMembersRequest;
import org.example.ohara.organization.api.dto.AddCohortMembersResponse;
import org.example.ohara.organization.api.dto.CohortMemberResponse;
import org.example.ohara.organization.api.dto.CohortResponse;
import org.example.ohara.organization.api.dto.CreateCohortRequest;
import org.example.ohara.organization.api.dto.TransferCohortMemberRequest;
import org.example.ohara.organization.api.dto.UpdateCohortRequest;
import org.example.ohara.organization.application.CohortService;
import org.example.ohara.organization.domain.Cohort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orgs/{orgId}/cohorts")
public class CohortController {

    private final CohortService cohorts;

    public CohortController(CohortService cohorts) {
        this.cohorts = cohorts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CohortResponse create(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                 @Valid @RequestBody CreateCohortRequest request) {
        return cohorts.create(actor, orgId, request);
    }

    @GetMapping
    public List<CohortResponse> list(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                     @RequestParam(required = false) Cohort.Status status) {
        return cohorts.list(actor, orgId, status);
    }

    @GetMapping("/{cohortId}")
    public CohortResponse get(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId, @PathVariable UUID cohortId) {
        return cohorts.get(actor, orgId, cohortId);
    }

    @PatchMapping("/{cohortId}")
    public CohortResponse update(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                 @PathVariable UUID cohortId, @Valid @RequestBody UpdateCohortRequest request) {
        return cohorts.update(actor, orgId, cohortId, request);
    }

    @GetMapping("/{cohortId}/members")
    public List<CohortMemberResponse> members(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                              @PathVariable UUID cohortId,
                                              @RequestParam(defaultValue = "false") boolean includeFormer) {
        return cohorts.members(actor, orgId, cohortId, includeFormer);
    }

    @PostMapping("/{cohortId}/members")
    public AddCohortMembersResponse addMembers(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                               @PathVariable UUID cohortId, @Valid @RequestBody AddCohortMembersRequest request) {
        return cohorts.addMembers(actor, orgId, cohortId, request.userIds());
    }

    @DeleteMapping("/{cohortId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                             @PathVariable UUID cohortId, @PathVariable UUID userId) {
        cohorts.removeMember(actor, orgId, cohortId, userId);
    }

    @PostMapping("/{cohortId}/members/{userId}/transfer")
    public CohortMemberResponse transfer(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                         @PathVariable UUID cohortId, @PathVariable UUID userId,
                                         @Valid @RequestBody TransferCohortMemberRequest request) {
        return cohorts.transfer(actor, orgId, cohortId, userId, request.toCohortId());
    }
}
