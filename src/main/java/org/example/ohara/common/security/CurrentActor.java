package org.example.ohara.common.security;

import java.util.List;
import java.util.UUID;

/** Authenticated principal built from a validated access token. Platform roles only; org roles are resolved server-side. */
public record CurrentActor(UUID userId, String email, List<String> platformRoles) {

    public boolean hasPlatformRole(String role) {
        return platformRoles.contains(role);
    }
}
