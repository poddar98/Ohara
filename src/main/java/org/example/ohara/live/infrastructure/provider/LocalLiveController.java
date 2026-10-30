package org.example.ohara.live.infrastructure.provider;

import org.example.ohara.live.application.LiveStreamEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The "IVS side" of local development. An encoder connecting or disconnecting is simulated with the stream-start /
 * stream-end calls (on IVS these are EventBridge stream state changes); playback is served only for a valid token.
 */
@RestController
@ConditionalOnProperty(name = "ohara.live.provider", havingValue = "local", matchIfMissing = true)
class LocalLiveController {

    private final LocalLiveProvider provider;
    private final LiveStreamEvents events;

    LocalLiveController(LocalLiveProvider provider, LiveStreamEvents events) {
        this.provider = provider;
        this.events = events;
    }

    @PostMapping("/dev-live/channels/{channelRef}/stream-start")
    ResponseEntity<Void> streamStart(@PathVariable String channelRef) {
        provider.encoderConnected(channelRef);
        if (!events.streamStarted(channelRef, Instant.now())) {
            provider.stopStream(channelRef);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/dev-live/channels/{channelRef}/stream-end")
    ResponseEntity<Void> streamEnd(@PathVariable String channelRef) {
        provider.stopStream(channelRef);
        events.streamEnded(channelRef, Instant.now());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dev-live/playback/{channelRef}.m3u8")
    ResponseEntity<String> playback(@PathVariable String channelRef, @RequestParam(required = false) String token) {
        if (token == null || !provider.verifyPlayback(token).map(channelRef::equals).orElse(false)) {
            return ResponseEntity.status(403).build();
        }
        if (!provider.isOnAir(channelRef)) {
            return ResponseEntity.notFound().build();
        }
        String playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2.0,\nlive_00000.ts\n";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
            .cacheControl(CacheControl.noStore())
            .body(playlist);
    }
}
