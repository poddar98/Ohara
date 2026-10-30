package org.example.ohara.course.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.ohara.course.domain.Course;

import java.util.UUID;

/** {@code ownerOrganizationId} null = platform catalog course. Visibility defaults to PUBLIC (platform) / ORG_PRIVATE (org). */
public record CreateCourseRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,126}[a-z0-9]$",
        message = "Slug must be 3-128 lowercase letters, digits or hyphens") String slug,
    UUID ownerOrganizationId,
    Course.Visibility visibility
) {
}
