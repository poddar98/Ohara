package org.example.ohara.course.infrastructure.persistence;

import org.example.ohara.course.domain.CourseVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Versions are always looked up together with their course id, so a version can't be reached through another course. */
public interface CourseVersionRepository extends JpaRepository<CourseVersion, UUID> {

    Optional<CourseVersion> findByIdAndCourseId(UUID id, UUID courseId);

    List<CourseVersion> findByCourseIdOrderByVersionNumberDesc(UUID courseId);

    Optional<CourseVersion> findByCourseIdAndStatus(UUID courseId, CourseVersion.Status status);

    @Query("select coalesce(max(v.versionNumber), 0) from CourseVersion v where v.courseId = :courseId")
    int maxVersionNumber(@Param("courseId") UUID courseId);
}
