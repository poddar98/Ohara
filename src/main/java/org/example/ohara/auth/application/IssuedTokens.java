package org.example.ohara.auth.application;

import java.time.LocalDateTime;

/** Raw tokens produced by a login, register or refresh. The controller decides how to deliver them. */
public record IssuedTokens(String accessToken, String refreshToken, LocalDateTime refreshExpiresAt, String email) {
}
