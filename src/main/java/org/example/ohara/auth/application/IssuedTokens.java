package org.example.ohara.auth.application;

import java.time.Instant;

/** Raw tokens produced by a login, register or refresh. The controller decides how to deliver them. */
public record IssuedTokens(String accessToken, String refreshToken, Instant refreshExpiresAt, String email) {
}
