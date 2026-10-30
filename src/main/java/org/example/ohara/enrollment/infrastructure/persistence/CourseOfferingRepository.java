package org.example.ohara.enrollment.infrastructure.persistence;

import org.example.ohara.enrollment.domain.CourseOffering;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CourseOfferingRepository extends JpaRepository<CourseOffering, UUID> {

    Optional<CourseOffering> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<CourseOffering> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<CourseOffering> findByCohortIdAndStatus(UUID cohortId, CourseOffering.Status status);

    List<CourseOffering> findByChannelOrderByCreatedAtDesc(CourseOffering.Channel channel);

    boolean existsByCourseIdAndCohortIdAndStatusNot(UUID courseId, UUID cohortId, CourseOffering.Status status);
}
