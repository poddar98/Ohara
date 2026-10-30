package org.example.ohara.content.infrastructure.cdn;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.example.ohara.content.application.ObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;

/**
 * The "CloudFront edge" of local development: serves delivery files only to holders of a valid signed cookie whose
 * policy covers the requested key. Segments are immutable (paths carry the processing generation), playlists short-lived.
 */
@RestController
@ConditionalOnProperty(name = "ohara.cdn.provider", havingValue = "local", matchIfMissing = true)
class LocalCdnController {

    private final LocalCdn cdn;
    private final ObjectStorage storage;

    LocalCdnController(LocalCdn cdn, ObjectStorage storage) {
        this.cdn = cdn;
        this.storage = storage;
    }

    @GetMapping("/dev-cdn/**")
    ResponseEntity<byte[]> serve(HttpServletRequest request) throws IOException {
        String key = request.getRequestURI().substring(request.getContextPath().length() + LocalCdn.PATH.length());
        if (key.contains("..") || !key.startsWith("delivery/")) {
            return ResponseEntity.status(403).build();
        }
        boolean authorized = request.getCookies() != null && Arrays.stream(request.getCookies())
            .filter(c -> LocalCdn.COOKIE.equals(c.getName()))
            .map(Cookie::getValue)
            .map(cdn::verify)
            .anyMatch(prefix -> prefix.isPresent() && key.startsWith(prefix.get()));
        if (!authorized) {
            return ResponseEntity.status(403).build();
        }
        if (storage.head(key).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = storage.open(key)) {
            boolean playlist = key.endsWith(".m3u8");
            return ResponseEntity.ok()
                .contentType(playlist ? MediaType.parseMediaType("application/vnd.apple.mpegurl") : MediaType.parseMediaType("video/mp2t"))
                .cacheControl(playlist ? CacheControl.maxAge(Duration.ofMinutes(5)) : CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(in.readAllBytes());
        }
    }
}
