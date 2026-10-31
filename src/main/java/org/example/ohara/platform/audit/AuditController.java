package org.example.ohara.platform.audit;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.common.web.PageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** The platform-wide audit trail, for platform admins and support. Org admins use their organization's view. */
@RestController
class AuditController {

    private final AuditQueries audit;

    AuditController(AuditQueries audit) {
        this.audit = audit;
    }

    @GetMapping("/api/admin/audit")
    PageResponse<AuditQueries.Event> search(@AuthenticationPrincipal CurrentActor actor,
                                            @RequestParam(required = false) UUID organizationId,
                                            @RequestParam(required = false) UUID actorUserId,
                                            @RequestParam(required = false) String action,
                                            @RequestParam(required = false) String resourceType,
                                            @RequestParam(required = false) String resourceId,
                                            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        if (!actor.isPlatformAdmin() && !actor.isPlatformSupport()) {
            throw new ApiException.Forbidden("forbidden", "Only platform staff can read the platform audit trail");
        }
        return audit.page(new AuditQueries.Filter(organizationId, actorUserId, action, resourceType, resourceId, from, to),
            Math.max(page, 0), PageResponse.clampSize(size));
    }
}
