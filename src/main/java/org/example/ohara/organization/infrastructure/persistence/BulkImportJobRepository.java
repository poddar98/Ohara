package org.example.ohara.organization.infrastructure.persistence;

import org.example.ohara.organization.domain.BulkImportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BulkImportJobRepository extends JpaRepository<BulkImportJob, UUID> {

    Optional<BulkImportJob> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<BulkImportJob> findTop50ByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    boolean existsByOrganizationIdAndFileSha256AndStatusNot(UUID organizationId, String fileSha256, BulkImportJob.Status status);
}
