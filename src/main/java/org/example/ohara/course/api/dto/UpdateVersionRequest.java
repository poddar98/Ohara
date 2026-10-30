package org.example.ohara.course.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.ohara.course.domain.CourseVersion;

/** Null fields are unchanged. Drafts only. */
public record UpdateVersionRequest(
    @Size(min = 1, max = 200) String title,
    @Size(max = 300) String subtitle,
    @Size(max = 20000) String description,
    @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$", message = "Language must be like 'en' or 'en-IN'") String language,
    CourseVersion.Level level,
    @Min(0) @Max(100000) Integer estimatedMinutes
) {
}
