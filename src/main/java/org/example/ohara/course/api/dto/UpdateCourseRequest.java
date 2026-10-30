package org.example.ohara.course.api.dto;

import jakarta.validation.constraints.Size;
import org.example.ohara.course.domain.Course;

public record UpdateCourseRequest(@Size(min = 1, max = 200) String title, Course.Visibility visibility) {
}
