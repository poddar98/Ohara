package org.example.ohara.enrollment.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.enrollment.api.dto.ExtendGrantRequest;
import org.example.ohara.enrollment.api.dto.GrantReportRow;
import org.example.ohara.enrollment.api.dto.GrantRequest;
import org.example.ohara.enrollment.api.dto.GrantResponse;
import org.example.ohara.enrollment.api.dto.RevokeGrantRequest;
import org.example.ohara.enrollment.application.AccessGrantService;
import org.example.ohara.enrollment.domain.AccessGrant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contract team endpoints (ROLE_ENROLLMENT_MANAGER or platform admin). */
@RestController
public class AccessGrantController {

    private final AccessGrantService grants;

    public AccessGrantController(AccessGrantService grants) {
        this.grants = grants;
    }

    /** 201 for a new grant; 200 when the same reference was already recorded for this learner. */
    @PostMapping("/api/admin/offerings/{offeringId}/grants")
    public ResponseEntity<GrantResponse> grant(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID offeringId,
                                               @Valid @RequestBody GrantRequest request) {
        GrantResponse response = grants.grant(actor, offeringId, request);
        return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }

    @PostMapping("/api/admin/grants/{grantId}/extend")
    @ResponseStatus(HttpStatus.CREATED)
    public GrantResponse extend(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID grantId,
                                @Valid @RequestBody ExtendGrantRequest request) {
        return grants.extend(actor, grantId, request);
    }

    @PostMapping("/api/admin/grants/{grantId}/revoke")
    public GrantResponse revoke(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID grantId,
                                @Valid @RequestBody RevokeGrantRequest request) {
        return grants.revoke(actor, grantId, request.reason());
    }

    @GetMapping("/api/admin/access-grants")
    public List<GrantReportRow> report(@AuthenticationPrincipal CurrentActor actor,
                                       @RequestParam(required = false) AccessGrant.Source source,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return grants.report(actor, source, from(from), to(to));
    }

    @GetMapping(value = "/api/admin/access-grants.csv", produces = "text/csv")
    public ResponseEntity<String> reportCsv(@AuthenticationPrincipal CurrentActor actor,
                                            @RequestParam(required = false) AccessGrant.Source source,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        String csv = AccessGrantService.toCsv(grants.report(actor, source, from(from), to(to)));
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"access-grants.csv\"")
            .contentType(new MediaType("text", "csv"))
            .body(csv);
    }

    private static Instant from(Instant from) {
        return from != null ? from : Instant.EPOCH;
    }

    private static Instant to(Instant to) {
        return to != null ? to : Instant.now().plusSeconds(1);
    }
}
