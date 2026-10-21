package org.example.ohara.organization.api.dto;

import org.example.ohara.organization.domain.BulkImportJob;
import org.example.ohara.organization.domain.OrgRole;

import java.time.Instant;
import java.util.UUID;

/** Counters: {@code failedRows} includes rows rejected at validation; {@code skippedRows} were already members or cancelled. */
public record ImportResponse(UUID id, BulkImportJob.Status status, BulkImportJob.Mode mode, OrgRole role, UUID cohortId,
                             String fileName, int totalRows, int validRows, int succeededRows, int failedRows,
                             int skippedRows, Instant createdAt, Instant confirmedAt, Instant finishedAt) {

    public static ImportResponse of(BulkImportJob job) {
        return new ImportResponse(job.getId(), job.getStatus(), job.getMode(), job.getRole(), job.getCohortId(),
            job.getFileName(), job.getTotalRows(), job.getValidRows(), job.getSucceededRows(), job.getFailedRows(),
            job.getSkippedRows(), job.getCreatedAt(), job.getConfirmedAt(), job.getFinishedAt());
    }
}
