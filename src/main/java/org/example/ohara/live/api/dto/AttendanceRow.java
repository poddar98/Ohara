package org.example.ohara.live.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AttendanceRow(UUID userId, String email, String name, Instant firstJoinedAt, Instant lastSeenAt, int joinCount,
                            int attendedSeconds, String status, String source, String note) {
}
