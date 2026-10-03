package org.example.ohara.auth;

public record AuthResponse(String accessToken, String refreshToken, String tokenType, String email) {
}
