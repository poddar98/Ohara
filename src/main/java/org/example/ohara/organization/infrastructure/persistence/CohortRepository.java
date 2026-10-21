package org.example.ohara.organization.infrastructure.persistence;

import org.example.ohara.organization.domain.Cohort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CohortRepository extends JpaRepository<Cohort, UUID> {

    Optional<Cohort> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Cohort> findByOrganizationIdOrderByStartsOnDescNameAsc(UUID organizationId);

    List<Cohort> findByOrganizationIdAndStatusOrderByStartsOnDescNameAsc(UUID organizationId, Cohort.Status status);

    List<Cohort> findByIdIn(Collection<UUID> ids);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);
}
