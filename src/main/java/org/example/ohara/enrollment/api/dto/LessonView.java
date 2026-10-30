package org.example.ohara.enrollment.api.dto;

import org.example.ohara.course.api.dto.VersionTree;

import java.util.List;
import java.util.UUID;

/** {@code enrollmentId} is null for course staff and for free preview lessons viewed without an enrollment. */
public record LessonView(UUID lessonId, UUID lessonKey, String title, String type, UUID enrollmentId,
                         List<VersionTree.ContentNode> contents) {
}
