package org.example.ohara.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A CSV import of people into an organization. Uploading validates every row (status VALIDATED, nothing applied);
 * confirming queues the rows for processing, which is resumable and never rolls back rows already applied.
 */
@Entity
@Table(name = "bulk_import_job")
public class BulkImportJob {

    public enum Mode { INVITE, CREATE_ACCOUNTS }

    public enum Status { VALIDATED, PROCESSING, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "cohort_id")
    private UUID cohortId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgRole role;

    @Column(nullable = false)
    private String fileName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.VALIDATED;

    @Column(nullable = false)
    private int totalRows;

    @Column(nullable = false)
    private int validRows;

    @Column(nullable = false)
    private int succeededRows;

    @Column(nullable = false)
    private int failedRows;

    @Column(nullable = false)
    private int skippedRows;

    @Column(nullable = false)
    private UUID uploadedBy;

    @Column
    private Instant confirmedAt;

    @Column
    private Instant finishedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected BulkImportJob() {
    }

    public BulkImportJob(UUID organizationId, UUID cohortId, Mode mode, OrgRole role, String fileName, String fileSha256,
                         UUID uploadedBy) {
        Instant now = Instant.now();
        this.organizationId = organizationId;
        this.cohortId = cohortId;
        this.mode = mode;
        this.role = role;
        this.fileName = fileName;
        this.fileSha256 = fileSha256;
        this.uploadedBy = uploadedBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void recordValidation(int total, int valid) {
        this.totalRows = total;
        this.validRows = valid;
        this.failedRows = total - valid;
        this.updatedAt = Instant.now();
    }

    public void confirm() {
        this.status = Status.PROCESSING;
        this.confirmedAt = Instant.now();
        this.updatedAt = confirmedAt;
    }

    public void cancel() {
        this.status = Status.CANCELLED;
        this.finishedAt = Instant.now();
        this.updatedAt = finishedAt;
    }

    /** Final counters; {@code failed} includes rows that were invalid at upload. */
    public void finish(int succeeded, int failed, int skipped) {
        this.succeededRows = succeeded;
        this.failedRows = failed;
        this.skippedRows = skipped;
        if (status != Status.CANCELLED) {
            this.status = failed > 0 ? Status.COMPLETED_WITH_ERRORS : Status.COMPLETED;
        }
        this.finishedAt = Instant.now();
        this.updatedAt = finishedAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getCohortId() { return cohortId; }
    public Mode getMode() { return mode; }
    public OrgRole getRole() { return role; }
    public String getFileName() { return fileName; }
    public Status getStatus() { return status; }
    public int getTotalRows() { return totalRows; }
    public int getValidRows() { return validRows; }
    public int getSucceededRows() { return succeededRows; }
    public int getFailedRows() { return failedRows; }
    public int getSkippedRows() { return skippedRows; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
