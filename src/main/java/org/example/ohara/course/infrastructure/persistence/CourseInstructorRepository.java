package org.example.ohara.course.infrastructure.persistence;

import org.example.ohara.course.domain.CourseInstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CourseInstructorRepository extends JpaRepository<CourseInstructor, CourseInstructor.Key> {

    @Query("select ci from CourseInstructor ci where ci.id.courseId = :courseId order by ci.addedAt")
    List<CourseInstructor> findByCourse(@Param("courseId") UUID courseId);

    @Query("select count(ci) from CourseInstructor ci where ci.id.courseId = :courseId and ci.role = 'OWNER'")
    long countOwners(@Param("courseId") UUID courseId);
}
