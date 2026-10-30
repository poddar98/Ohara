package org.example.ohara.enrollment.infrastructure.persistence;

import org.example.ohara.enrollment.domain.AccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessGrantRepository extends JpaRepository<AccessGrant, UUID> {

    List<AccessGrant> findByEnrollmentIdOrderByCreatedAt(UUID enrollmentId);

    @Query("select g from AccessGrant g where g.enrollmentId in :ids and g.revokedAt is null")
    List<AccessGrant> findUnrevoked(@Param("ids") Collection<UUID> enrollmentIds);

    Optional<AccessGrant> findByEnrollmentIdAndSourceAndSourceRefId(UUID enrollmentId, AccessGrant.Source source, UUID sourceRefId);

    List<AccessGrant> findBySourceRefIdAndRevokedAtIsNull(UUID sourceRefId);

    @Query("select g from AccessGrant g where g.enrollmentId in (select e.id from Enrollment e where e.courseOfferingId = :offeringId) "
        + "and g.source = 'COHORT_ASSIGNMENT' and g.revokedAt is null")
    List<AccessGrant> findLiveCohortGrants(@Param("offeringId") UUID offeringId);

    @Query("""
        select g from AccessGrant g
        where (:source is null or g.source = :source)
          and g.createdAt >= :from and g.createdAt < :to
        order by g.createdAt
        """)
    List<AccessGrant> findForReport(@Param("source") AccessGrant.Source source, @Param("from") Instant from, @Param("to") Instant to);

    List<AccessGrant> findBySourceAndExternalReference(AccessGrant.Source source, String externalReference);
}
