package org.example.ohara.enrollment.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.example.ohara.enrollment.domain.LessonProgress;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LessonProgressRepository extends JpaRepository<LessonProgress, UUID> {

    /** Row lock so concurrent heartbeats for the same lesson serialize instead of losing credited time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LessonProgress p where p.enrollmentId = :enrollmentId and p.lessonKey = :lessonKey")
    Optional<LessonProgress> lockOne(@Param("enrollmentId") UUID enrollmentId, @Param("lessonKey") UUID lessonKey);

    List<LessonProgress> findByEnrollmentId(UUID enrollmentId);

    @Query("select p from LessonProgress p where p.userId = :userId and p.status = 'IN_PROGRESS' order by p.updatedAt desc")
    List<LessonProgress> findRecentInProgress(@Param("userId") UUID userId, Pageable limit);
}
