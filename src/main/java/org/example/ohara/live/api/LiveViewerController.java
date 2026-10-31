package org.example.ohara.live.api;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.content.application.CdnSigner;
import org.example.ohara.live.api.dto.JoinResponse;
import org.example.ohara.live.api.dto.LiveHeartbeatResponse;
import org.example.ohara.live.api.dto.LiveSessionResponse;
import org.example.ohara.live.api.dto.ReplayResponse;
import org.example.ohara.live.application.LiveViewerService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class LiveViewerController {

    private final LiveViewerService live;

    public LiveViewerController(LiveViewerService live) {
        this.live = live;
    }

    @GetMapping("/api/me/live-sessions")
    public List<LiveSessionResponse> mine(@AuthenticationPrincipal CurrentActor actor,
                                          @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return live.mySessions(actor, from, to);
    }

    @PostMapping("/api/live-sessions/{sessionId}/join")
    public ResponseEntity<JoinResponse> join(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(live.join(actor, sessionId));
    }

    @PostMapping("/api/live-sessions/{sessionId}/replay")
    public ResponseEntity<ReplayResponse> replay(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID sessionId) {
        LiveViewerService.Replay replay = live.replay(actor, sessionId);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        for (CdnSigner.Cookie c : replay.cookies()) {
            ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(c.name(), c.value())
                .path(c.path()).maxAge(c.maxAgeSeconds()).secure(c.secure()).httpOnly(c.httpOnly()).sameSite(c.sameSite());
            if (c.domain() != null) {
                cookie.domain(c.domain());
            }
            response.header(HttpHeaders.SET_COOKIE, cookie.build().toString());
        }
        return response.body(replay.response());
    }

    @PostMapping("/api/live-sessions/{sessionId}/heartbeat")
    public LiveHeartbeatResponse heartbeat(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID sessionId) {
        return live.heartbeat(actor, sessionId);
    }
}
