package org.example.ohara.enrollment.infrastructure.persistence;

import org.example.ohara.enrollment.domain.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    Optional<Enrollment> findByUserIdAndCourseOfferingId(UUID userId, UUID courseOfferingId);

    List<Enrollment> findByUserIdOrderByEnrolledAtDesc(UUID userId);

    List<Enrollment> findByUserIdAndCourseId(UUID userId, UUID courseId);

    List<Enrollment> findByCourseOfferingIdOrderByEnrolledAt(UUID courseOfferingId);

    Optional<Enrollment> findByIdAndUserId(UUID id, UUID userId);
}
