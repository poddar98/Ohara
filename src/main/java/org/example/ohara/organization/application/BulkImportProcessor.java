package org.example.ohara.organization.application;

import org.example.ohara.auth.application.AccountProvisioning;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.organization.domain.BulkImportJob;
import org.example.ohara.organization.domain.BulkImportRow;
import org.example.ohara.organization.domain.Cohort;
import org.example.ohara.organization.infrastructure.persistence.BulkImportJobRepository;
import org.example.ohara.organization.infrastructure.persistence.BulkImportRowRepository;
import org.example.ohara.organization.infrastructure.persistence.CohortRepository;
import org.example.ohara.platform.jobs.JobHandler;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies a confirmed import. Each row is its own transaction, so one bad row never undoes the others, and a crash
 * resumes with the rows still PENDING (the job is retried by the job runner). Rows are re-checked against current state
 * when applied, so a row applied twice after a crash ends up SKIPPED_DUPLICATE or LINKED_EXISTING, never duplicated.
 */
@Component
class BulkImportProcessor implements JobHandler<BulkImportService.ImportWork> {

    private static final Logger log = LoggerFactory.getLogger(BulkImportProcessor.class);
    private static final int CHUNK = 100;

    private final BulkImportJobRepository jobs;
    private final BulkImportRowRepository rows;
    private final CohortRepository cohorts;
    private final InvitationService invitations;
    private final MembershipGrants grants;
    private final AccountProvisioning accounts;
    private final TransactionTemplate tx;

    BulkImportProcessor(BulkImportJobRepository jobs, BulkImportRowRepository rows, CohortRepository cohorts,
                        InvitationService invitations, MembershipGrants grants, AccountProvisioning accounts,
                        TransactionTemplate tx) {
        this.jobs = jobs;
        this.rows = rows;
        this.cohorts = cohorts;
        this.invitations = invitations;
        this.grants = grants;
        this.accounts = accounts;
        this.tx = tx;
    }

    @Override
    public String jobType() {
        return BulkImportService.JOB_TYPE;
    }

    @Override
    public Class<BulkImportService.ImportWork> payloadType() {
        return BulkImportService.ImportWork.class;
    }

    @Override
    public void handle(BulkImportService.ImportWork work) {
        UUID importId = work.importId();
        while (true) {
            BulkImportJob job = jobs.findById(importId).orElse(null);
            if (job == null) {
                return;
            }
            if (job.getStatus() == BulkImportJob.Status.CANCELLED) {
                tx.executeWithoutResult(s -> rows.cancelPending(importId));
                break;
            }
            List<UUID> pending = rows.findPendingIds(importId, PageRequest.ofSize(CHUNK));
            if (pending.isEmpty()) {
                break;
            }
            pending.forEach(rowId -> process(job, rowId));
        }
        finish(importId);
    }

    private void process(BulkImportJob job, UUID rowId) {
        try {
            tx.executeWithoutResult(s -> apply(job, rows.findById(rowId).orElseThrow()));
        } catch (ApiException ex) {
            boolean duplicate = "already_member".equals(ex.getCode());
            tx.executeWithoutResult(s -> {
                BulkImportRow row = rows.findById(rowId).orElseThrow();
                if (duplicate) {
                    row.done(BulkImportRow.Status.SKIPPED_DUPLICATE, null, null);
                } else {
                    row.failed(ex.getCode().toUpperCase(), ex.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            log.error("Import {} row {} failed unexpectedly", job.getId(), rowId, ex);
            tx.executeWithoutResult(s -> rows.findById(rowId).orElseThrow().failed("UNEXPECTED_ERROR", "Could not be applied; retry or add manually"));
        }
    }

    private void apply(BulkImportJob job, BulkImportRow row) {
        if (row.getStatus() != BulkImportRow.Status.PENDING) {
            return;
        }
        Cohort cohort = job.getCohortId() == null ? null : cohorts.findById(job.getCohortId()).orElse(null);
        UUID orgId = job.getOrganizationId();

        if (job.getMode() == BulkImportJob.Mode.INVITE) {
            InvitationService.InviteResult result = invitations.send(orgId, job.getUploadedBy(), row.getEmail(), job.getRole(),
                cohort, row.getFirstName(), row.getLastName(), row.getExternalRef());
            row.done(BulkImportRow.Status.INVITED, null, result.invitation().id());
            return;
        }

        Optional<User> existing = accounts.findAccount(row.getEmail());
        if (existing.isPresent() && existing.get().getStatus() == UserStatus.ANONYMIZED) {
            throw new ApiException.Unprocessable("account_deleted", "The account for this email was deleted");
        }
        if (existing.isPresent()) {
            User user = existing.get();
            if (grants.holdsRole(orgId, user.getId(), job.getRole())
                && (cohort == null || grants.isPlaced(cohort.getId(), user.getId()))) {
                row.done(BulkImportRow.Status.SKIPPED_DUPLICATE, user.getId(), null);
                return;
            }
            grants.grant(orgId, user, job.getRole(), row.getExternalRef());
            if (cohort != null) {
                grants.place(cohort, user);
            }
            row.done(BulkImportRow.Status.LINKED_EXISTING, user.getId(), null);
            return;
        }

        User created = accounts.createAccountWithoutPassword(row.getEmail(), row.getFirstName(), row.getLastName());
        grants.grant(orgId, created, job.getRole(), row.getExternalRef());
        if (cohort != null) {
            grants.place(cohort, created);
        }
        row.done(BulkImportRow.Status.CREATED, created.getId(), null);
    }

    private void finish(UUID importId) {
        tx.executeWithoutResult(s -> {
            BulkImportJob job = jobs.findById(importId).orElseThrow();
            int succeeded = (int) rows.countByJobIdAndStatusIn(importId,
                EnumSet.of(BulkImportRow.Status.INVITED, BulkImportRow.Status.CREATED, BulkImportRow.Status.LINKED_EXISTING));
            int failed = (int) rows.countByJobIdAndStatusIn(importId,
                EnumSet.of(BulkImportRow.Status.INVALID, BulkImportRow.Status.FAILED));
            int skipped = (int) rows.countByJobIdAndStatusIn(importId,
                EnumSet.of(BulkImportRow.Status.SKIPPED_DUPLICATE, BulkImportRow.Status.CANCELLED));
            job.finish(succeeded, failed, skipped);
        });
    }
}
