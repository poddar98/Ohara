package org.example.ohara.live.api.dto;

/** The session's status lets the player notice the class ended. */
public record LiveHeartbeatResponse(String status, int attendedSeconds) {
}
