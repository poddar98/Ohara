package org.example.ohara.enrollment.api.dto;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code enrollmentId} is null for course staff and for free preview lessons viewed without an enrollment;
 * {@code accessUntil} is when that enrollment's access ends (null = open-ended or not enrollment-based).
 */
public record LessonView(UUID lessonId, UUID lessonKey, String title, String type, UUID enrollmentId,
                         Long resumePositionMs, Instant accessUntil, List<ContentItem> contents) {

    /**
     * {@code downloadUrl}: a short-lived link for documents. {@code available} is false when an asset is missing,
     * still processing or retired. Video playback is authorized separately (playback sessions).
     */
    public record ContentItem(UUID id, String kind, String title, String body, String externalUrl, UUID assetId,
                              boolean available, URI downloadUrl, int position, boolean required) {
    }
}
