package org.example.ohara.user.api.dto;

import java.util.List;
import java.util.UUID;

public record UserResponse(
    UUID id,
    String email,
    String firstName,
    String lastName,
    String status,
    boolean emailVerified,
    boolean termsAccepted,
    List<String> roles
) {
}
