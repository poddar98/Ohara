package org.example.ohara.course.api.dto;

import jakarta.validation.constraints.NotNull;
import org.example.ohara.course.domain.CourseInstructor;

import java.util.UUID;

public record InstructorRequest(@NotNull UUID userId, @NotNull CourseInstructor.Role role) {
}
