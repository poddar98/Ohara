package org.example.ohara.live.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.live.api.dto.AttendanceRow;
import org.example.ohara.live.api.dto.CancelLiveSessionRequest;
import org.example.ohara.live.api.dto.IngestResponse;
import org.example.ohara.live.api.dto.LiveSessionResponse;
import org.example.ohara.live.api.dto.OverrideAttendanceRequest;
import org.example.ohara.live.api.dto.ScheduleLiveSessionRequest;
import org.example.ohara.live.api.dto.UpdateLiveSessionRequest;
import org.example.ohara.live.application.LiveScheduleService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orgs/{orgId}/live-sessions")
public class LiveSessionController {

    private final LiveScheduleService live;

    public LiveSessionController(LiveScheduleService live) {
        this.live = live;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LiveSessionResponse schedule(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                        @Valid @RequestBody ScheduleLiveSessionRequest request) {
        return live.schedule(actor, orgId, request);
    }

    @GetMapping
    public List<LiveSessionResponse> list(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                          @RequestParam(required = false) UUID cohortId,
                                          @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return live.list(actor, orgId, cohortId, from, to);
    }

    @GetMapping("/{sessionId}")
    public LiveSessionResponse get(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId, @PathVariable UUID sessionId) {
        return live.get(actor, orgId, sessionId);
    }

    @PatchMapping("/{sessionId}")
    public LiveSessionResponse update(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                      @PathVariable UUID sessionId, @Valid @RequestBody UpdateLiveSessionRequest request) {
        return live.update(actor, orgId, sessionId, request);
    }

    @PostMapping("/{sessionId}/cancel")
    public LiveSessionResponse cancel(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                      @PathVariable UUID sessionId, @Valid @RequestBody CancelLiveSessionRequest request) {
        return live.cancel(actor, orgId, sessionId, request.reason());
    }

    /** Contains the stream key: never cached. */
    @GetMapping("/{sessionId}/ingest")
    public ResponseEntity<IngestResponse> ingest(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                                 @PathVariable UUID sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(live.ingest(actor, orgId, sessionId));
    }

    @PostMapping("/{sessionId}/end")
    public LiveSessionResponse end(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId, @PathVariable UUID sessionId) {
        return live.end(actor, orgId, sessionId);
    }

    @GetMapping("/{sessionId}/attendance")
    public List<AttendanceRow> attendance(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                          @PathVariable UUID sessionId) {
        return live.attendance(actor, orgId, sessionId);
    }

    @PutMapping("/{sessionId}/attendance/{userId}")
    public AttendanceRow overrideAttendance(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                            @PathVariable UUID sessionId, @PathVariable UUID userId,
                                            @Valid @RequestBody OverrideAttendanceRequest request) {
        return live.overrideAttendance(actor, orgId, sessionId, userId, request);
    }
}
