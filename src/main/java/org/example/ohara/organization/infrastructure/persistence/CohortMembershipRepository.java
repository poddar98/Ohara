package org.example.ohara.organization.infrastructure.persistence;

import org.example.ohara.organization.domain.CohortMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CohortMembershipRepository extends JpaRepository<CohortMembership, UUID> {

    @Query("select cm from CohortMembership cm join fetch cm.user where cm.cohortId = :cohortId and cm.leftAt is null order by cm.joinedAt")
    List<CohortMembership> findOpenByCohort(@Param("cohortId") UUID cohortId);

    @Query("select cm from CohortMembership cm join fetch cm.user where cm.cohortId = :cohortId order by cm.joinedAt")
    List<CohortMembership> findAllByCohort(@Param("cohortId") UUID cohortId);

    @Query("select cm from CohortMembership cm where cm.cohortId = :cohortId and cm.user.id = :userId and cm.leftAt is null")
    Optional<CohortMembership> findOpen(@Param("cohortId") UUID cohortId, @Param("userId") UUID userId);

    @Query("select cm from CohortMembership cm where cm.cohortId = :cohortId and cm.user.id in :userIds and cm.leftAt is null")
    List<CohortMembership> findOpen(@Param("cohortId") UUID cohortId, @Param("userIds") Collection<UUID> userIds);

    @Query("select cm from CohortMembership cm where cm.organizationId = :orgId and cm.user.id = :userId and cm.leftAt is null")
    List<CohortMembership> findOpenInOrganization(@Param("orgId") UUID organizationId, @Param("userId") UUID userId);

    interface CohortCount {
        UUID getCohortId();
        long getMembers();
    }

    @Query("select cm.cohortId as cohortId, count(cm) as members from CohortMembership cm where cm.cohortId in :cohortIds and cm.leftAt is null group by cm.cohortId")
    List<CohortCount> countOpen(@Param("cohortIds") Collection<UUID> cohortIds);

    @Query("select cm from CohortMembership cm where cm.user.id = :userId and cm.leftAt is null")
    List<CohortMembership> findOpenByUser(@Param("userId") UUID userId);
}
