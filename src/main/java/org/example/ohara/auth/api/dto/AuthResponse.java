package org.example.ohara.auth.api.dto;

/** Response body. The refresh token is never in the body; it travels in an HttpOnly cookie. */
public record AuthResponse(String accessToken, String tokenType, String email) {
}
