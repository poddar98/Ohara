package org.example.ohara.organization.infrastructure.persistence;

import org.example.ohara.organization.domain.BulkImportRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BulkImportRowRepository extends JpaRepository<BulkImportRow, UUID> {

    @Query("select r.id from BulkImportRow r where r.jobId = :jobId and r.status = 'PENDING' order by r.rowNumber")
    List<UUID> findPendingIds(@Param("jobId") UUID jobId, Pageable limit);

    Page<BulkImportRow> findByJobIdOrderByRowNumber(UUID jobId, Pageable pageable);

    Page<BulkImportRow> findByJobIdAndStatusInOrderByRowNumber(UUID jobId, Collection<BulkImportRow.Status> statuses, Pageable pageable);

    List<BulkImportRow> findByJobIdAndStatusInOrderByRowNumber(UUID jobId, Collection<BulkImportRow.Status> statuses);

    long countByJobIdAndStatusIn(UUID jobId, Collection<BulkImportRow.Status> statuses);

    @Modifying
    @Query("update BulkImportRow r set r.status = 'CANCELLED' where r.jobId = :jobId and r.status = 'PENDING'")
    int cancelPending(@Param("jobId") UUID jobId);
}
