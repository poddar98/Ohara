package org.example.ohara.organization.infrastructure.persistence;

import org.example.ohara.organization.domain.OrgRole;
import org.example.ohara.organization.domain.OrganizationMembership;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every lookup is scoped by organization, so a caller can't reach another tenant's rows by id. */
public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {

    Optional<OrganizationMembership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    List<OrganizationMembership> findByOrganizationIdAndUserIdIn(UUID organizationId, Collection<UUID> userIds);

    List<OrganizationMembership> findByUserIdAndStatus(UUID userId, OrganizationMembership.Status status);

    @Query(value = """
        select m from OrganizationMembership m join fetch m.user
        where m.organizationId = :orgId
          and m.status in :statuses
          and (:role is null or :role member of m.roles)
        order by m.joinedAt, m.id
        """, countQuery = """
        select count(m) from OrganizationMembership m
        where m.organizationId = :orgId
          and m.status in :statuses
          and (:role is null or :role member of m.roles)
        """)
    Page<OrganizationMembership> search(@Param("orgId") UUID organizationId,
                                        @Param("statuses") Collection<OrganizationMembership.Status> statuses,
                                        @Param("role") OrgRole role,
                                        Pageable pageable);

    @Query("""
        select count(m) from OrganizationMembership m
        where m.organizationId = :orgId and m.status = 'ACTIVE' and :role member of m.roles
        """)
    long countActiveWithRole(@Param("orgId") UUID organizationId, @Param("role") OrgRole role);

    boolean existsByOrganizationIdAndExternalRefAndUserIdNot(UUID organizationId, String externalRef, UUID userId);
}
