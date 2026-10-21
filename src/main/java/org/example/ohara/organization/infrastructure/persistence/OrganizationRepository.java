package org.example.ohara.organization.infrastructure.persistence;

import org.example.ohara.organization.domain.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    boolean existsBySlug(String slug);

    Page<Organization> findByStatus(Organization.Status status, Pageable pageable);
}
