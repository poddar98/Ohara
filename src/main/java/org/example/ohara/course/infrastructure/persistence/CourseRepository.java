package org.example.ohara.course.infrastructure.persistence;

import org.example.ohara.course.domain.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface CourseRepository extends JpaRepository<Course, UUID> {

    @Query("select count(c) > 0 from Course c where c.slug = :slug and c.ownerOrganizationId is null")
    boolean platformSlugTaken(@Param("slug") String slug);

    boolean existsByOwnerOrganizationIdAndSlug(UUID ownerOrganizationId, String slug);

    Page<Course> findByOwnerOrganizationId(UUID ownerOrganizationId, Pageable pageable);

    @Query("select c from Course c where c.id in (select ci.id.courseId from CourseInstructor ci where ci.id.userId = :userId)")
    Page<Course> findTaughtBy(@Param("userId") UUID userId, Pageable pageable);
}
