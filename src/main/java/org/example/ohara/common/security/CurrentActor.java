package org.example.ohara.common.security;

import java.util.List;
import java.util.UUID;

/** Authenticated principal built from a validated access token. Platform roles only; org roles are resolved server-side. */
public record CurrentActor(UUID userId, String email, List<String> platformRoles) {

    public static final String PLATFORM_ADMIN = "ROLE_PLATFORM_ADMIN";
    public static final String PLATFORM_SUPPORT = "ROLE_PLATFORM_SUPPORT";

    public boolean hasPlatformRole(String role) {
        return platformRoles.contains(role);
    }

    public boolean isPlatformAdmin() {
        return hasPlatformRole(PLATFORM_ADMIN);
    }

    public boolean isPlatformSupport() {
        return hasPlatformRole(PLATFORM_SUPPORT);
    }
}
