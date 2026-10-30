package org.example.ohara.course.api.dto;

import org.example.ohara.course.domain.CourseInstructor;

import java.time.Instant;
import java.util.UUID;

public record InstructorResponse(UUID userId, String email, String name, CourseInstructor.Role role, Instant addedAt) {
}
