package org.example.ohara.course.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.example.ohara.course.domain.Lesson;

/** On create, {@code title} and {@code type} are required; on update, null fields are unchanged. */
public record LessonRequest(
    @Size(min = 1, max = 200) String title,
    Lesson.Type type,
    Boolean preview,
    @Min(0) Integer durationSeconds
) {
}
