package org.example.ohara.auth.api.dto;

public record AuthResponse(String accessToken, String refreshToken, String tokenType, String email) {
}
