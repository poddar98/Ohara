package org.example.ohara.user.application;

/** The minimum needed to decide whether an access token is still honoured. */
public record UserAuthState(boolean active, int authVersion) {
}
