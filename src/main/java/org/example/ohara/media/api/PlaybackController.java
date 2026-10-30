package org.example.ohara.media.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.content.application.CdnSigner;
import org.example.ohara.media.api.dto.PlaybackResponse;
import org.example.ohara.media.api.dto.StartPlaybackRequest;
import org.example.ohara.media.application.PlaybackService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PlaybackController {

    private final PlaybackService playback;

    public PlaybackController(PlaybackService playback) {
        this.playback = playback;
    }

    @PostMapping("/api/playback/sessions")
    public ResponseEntity<PlaybackResponse> start(@AuthenticationPrincipal CurrentActor actor,
                                                  @Valid @RequestBody StartPlaybackRequest request) {
        return withCookies(playback.start(actor, request.lessonId(), request.contentId()));
    }

    @PostMapping("/api/playback/sessions/{sessionId}/refresh")
    public ResponseEntity<PlaybackResponse> refresh(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID sessionId) {
        return withCookies(playback.refresh(actor, sessionId));
    }

    private static ResponseEntity<PlaybackResponse> withCookies(PlaybackService.Issued issued) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        for (CdnSigner.Cookie c : issued.cookies()) {
            ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(c.name(), c.value())
                .path(c.path()).maxAge(c.maxAgeSeconds()).secure(c.secure()).httpOnly(c.httpOnly()).sameSite(c.sameSite());
            if (c.domain() != null) {
                cookie.domain(c.domain());
            }
            response.header(HttpHeaders.SET_COOKIE, cookie.build().toString());
        }
        return response.body(issued.response());
    }
}
