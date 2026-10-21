package org.example.ohara.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bulk_import_row")
public class BulkImportRow {

    public enum Status { PENDING, INVITED, CREATED, LINKED_EXISTING, SKIPPED_DUPLICATE, INVALID, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private int rowNumber;

    @Column
    private String email;

    @Column
    private String firstName;

    @Column
    private String lastName;

    @Column
    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column
    private String errorCode;

    @Column
    private String errorDetail;

    @Column
    private UUID userId;

    @Column
    private UUID invitationId;

    @Column
    private Instant processedAt;

    protected BulkImportRow() {
    }

    public BulkImportRow(UUID jobId, int rowNumber, String email, String firstName, String lastName, String externalRef) {
        this.jobId = jobId;
        this.rowNumber = rowNumber;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.externalRef = externalRef;
        this.status = Status.PENDING;
    }

    public void invalid(String code, String detail) {
        this.status = Status.INVALID;
        this.errorCode = code;
        this.errorDetail = truncate(detail);
    }

    public void done(Status outcome, UUID userId, UUID invitationId) {
        this.status = outcome;
        this.userId = userId;
        this.invitationId = invitationId;
        this.processedAt = Instant.now();
    }

    public void failed(String code, String detail) {
        this.status = Status.FAILED;
        this.errorCode = code;
        this.errorDetail = truncate(detail);
        this.processedAt = Instant.now();
    }

    public boolean isInvalid() {
        return status == Status.INVALID;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public int getRowNumber() { return rowNumber; }
    public String getEmail() { return email; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getExternalRef() { return externalRef; }
    public Status getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public String getErrorDetail() { return errorDetail; }
    public UUID getUserId() { return userId; }
    public UUID getInvitationId() { return invitationId; }
}
