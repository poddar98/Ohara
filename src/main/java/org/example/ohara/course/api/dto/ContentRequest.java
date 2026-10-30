package org.example.ohara.course.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.ohara.course.domain.LessonContent;

/**
 * On create, {@code kind} is required with {@code body} (TEXT), {@code externalUrl} (EXTERNAL_LINK) or
 * {@code contentAssetId} (VIDEO, DOCUMENT — an asset of the course's owner).
 */
public record ContentRequest(
    LessonContent.Kind kind,
    @Size(max = 200) String title,
    @Size(max = 100000) String body,
    @Size(max = 2048) @Pattern(regexp = "^https?://\\S+$", message = "Must be an http(s) URL") String externalUrl,
    Boolean required,
    java.util.UUID contentAssetId
) {
}
