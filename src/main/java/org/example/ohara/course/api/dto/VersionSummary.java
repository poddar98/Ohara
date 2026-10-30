package org.example.ohara.course.api.dto;

import org.example.ohara.course.domain.CourseVersion;

import java.time.Instant;
import java.util.UUID;

public record VersionSummary(UUID id, int versionNumber, CourseVersion.Status status, String title,
                             Instant publishedAt, Instant archivedAt, Instant createdAt) {

    public static VersionSummary of(CourseVersion v) {
        return new VersionSummary(v.getId(), v.getVersionNumber(), v.getStatus(), v.getTitle(),
            v.getPublishedAt(), v.getArchivedAt(), v.getCreatedAt());
    }
}
