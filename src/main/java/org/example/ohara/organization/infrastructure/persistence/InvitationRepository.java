package org.example.ohara.organization.infrastructure.persistence;

import org.example.ohara.organization.domain.Invitation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    Optional<Invitation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("select i from Invitation i where i.organizationId = :orgId and lower(i.email) = lower(:email) and i.status = 'PENDING'")
    Optional<Invitation> findPending(@Param("orgId") UUID organizationId, @Param("email") String email);

    @Query("""
        select i from Invitation i where i.organizationId = :orgId
          and (:status is null or i.status = :status)
        order by i.createdAt desc, i.id
        """)
    Page<Invitation> search(@Param("orgId") UUID organizationId, @Param("status") Invitation.Status status, Pageable pageable);
}
