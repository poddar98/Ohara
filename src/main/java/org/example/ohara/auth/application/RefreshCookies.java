package org.example.ohara.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** The HttpOnly refresh-token cookie. Shared so every endpoint that starts a session sets it the same way. */
@Component
public class RefreshCookies {

    public static final String NAME = "refresh_token";
    private static final String PATH = "/api/auth";

    private final boolean secure;

    public RefreshCookies(@Value("${auth.refresh-cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie issue(IssuedTokens tokens) {
        return build(tokens.refreshToken(), Duration.between(Instant.now(), tokens.refreshExpiresAt()));
    }

    public ResponseCookie clear() {
        return build("", Duration.ZERO);
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path(PATH)
            .maxAge(maxAge)
            .build();
    }
}
