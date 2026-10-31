package org.example.ohara.auth.application;

import org.example.ohara.auth.api.dto.DeletionStatus;
import org.example.ohara.auth.domain.AuthIdentity;
import org.example.ohara.auth.infrastructure.persistence.PasswordCredentialRepository;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.platform.audit.AuditLog;
import org.example.ohara.platform.jobs.JobHandler;
import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Account deletion (docs 04 §3.10). The person asks with their password: the account closes at once (sessions
 * revoked, sign-in refused) and is anonymized after {@code grace}, during which support can cancel. A legal hold
 * (an open payment or refund matter, set by the contract team) defers anonymization week by week until released.
 * Anonymization removes credentials and tokens here, then {@link UserService#anonymize} scrubs every module.
 */
@Service
public class AccountDeletionService implements JobHandler<AccountDeletionService.AnonymizeWork> {

    static final String JOB = "ACCOUNT_ANONYMIZE";
    static final Duration HOLD_RECHECK = Duration.ofDays(7);

    public record AnonymizeWork(UUID userId, Instant dueAt) {
    }

    private final UserService users;
    private final PasswordCredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokens;
    private final AuthEmails emails;
    private final JobScheduler jobs;
    private final AuditLog audit;
    private final JdbcClient jdbc;
    private final Duration grace;

    AccountDeletionService(UserService users, PasswordCredentialRepository credentials, PasswordEncoder passwordEncoder,
                           TokenIssuer tokens, AuthEmails emails, JobScheduler jobs, AuditLog audit, JdbcClient jdbc,
                           @Value("${ohara.account.deletion-grace:30d}") Duration grace) {
        this.users = users;
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.emails = emails;
        this.jobs = jobs;
        this.audit = audit;
        this.jdbc = jdbc;
        this.grace = grace;
    }

    /** Idempotent: asking again while a deletion is pending returns it unchanged. */
    @Transactional
    public DeletionStatus request(CurrentActor actor, String password) {
        User user = user(actor.userId());
        boolean passwordOk = credentials.findByAuthIdentityProviderAndAuthIdentityIdentifier(AuthIdentity.LOCAL_PROVIDER,
                AccountRecoveryService.normalize(user.getEmail()))
            .map(c -> passwordEncoder.matches(password, c.getEncodedPassword()))
            .orElse(false);
        if (!passwordOk) {
            throw new ApiException.Forbidden("password_incorrect", "The password is incorrect");
        }
        if (user.isDeletionPending()) {
            return status(user);
        }
        Instant now = Instant.now();
        Instant due = now.plus(grace).truncatedTo(ChronoUnit.SECONDS);
        user.deletionRequested(now, due);
        users.save(user);
        tokens.revokeAllSessions(user, now);
        schedule(user.getId(), due);
        emails.deletionScheduled(user.getEmail(), due);
        audit.record(user.getId(), null, "ACCOUNT_DELETION_REQUESTED", "USER", user.getId(), AuditLog.details("dueAt", due));
        return status(user);
    }

    /** Support, at the person's request, before the deletion has run. */
    @Transactional
    public DeletionStatus cancel(CurrentActor actor, UUID userId) {
        requirePlatformAdmin(actor);
        User user = user(userId);
        if (!user.isDeletionPending()) {
            throw new ApiException.Conflict("no_deletion_pending", "This account has no pending deletion");
        }
        user.deletionCancelled(Instant.now());
        users.save(user);
        audit.record(actor.userId(), null, "ACCOUNT_DELETION_CANCELLED", "USER", userId, null);
        return status(user);
    }

    @Transactional
    public DeletionStatus placeLegalHold(CurrentActor actor, UUID userId, String reason) {
        requireContractTeamOrAdmin(actor);
        User user = user(userId);
        if (user.getStatus() == UserStatus.ANONYMIZED) {
            throw new ApiException.Conflict("account_anonymized", "This account was already anonymized");
        }
        user.legalHold(reason.strip());
        users.save(user);
        audit.record(actor.userId(), null, "LEGAL_HOLD_PLACED", "USER", userId, AuditLog.details("reason", reason.strip()));
        return status(user);
    }

    @Transactional
    public DeletionStatus releaseLegalHold(CurrentActor actor, UUID userId) {
        requireContractTeamOrAdmin(actor);
        User user = user(userId);
        if (user.getLegalHoldReason() != null) {
            user.legalHold(null);
            users.save(user);
            audit.record(actor.userId(), null, "LEGAL_HOLD_RELEASED", "USER", userId, null);
        }
        return status(user);
    }

    @Transactional(readOnly = true)
    public DeletionStatus get(CurrentActor actor, UUID userId) {
        if (!actor.isPlatformAdmin() && !actor.isPlatformSupport() && !actor.isEnrollmentManager()) {
            throw new ApiException.Forbidden("forbidden", "Only platform staff can see account deletion status");
        }
        return status(user(userId));
    }

    @Override
    public String jobType() {
        return JOB;
    }

    @Override
    public Class<AnonymizeWork> payloadType() {
        return AnonymizeWork.class;
    }

    /** ACCOUNT_ANONYMIZE. Stale jobs (cancelled or rescheduled deletions) do nothing. */
    @Override
    @Transactional
    public void handle(AnonymizeWork work) {
        User user = users.findById(work.userId()).orElse(null);
        if (user == null || !user.isDeletionPending() || !work.dueAt().equals(user.getDeletionDueAt())) {
            return;
        }
        if (user.getLegalHoldReason() != null) {
            Instant next = Instant.now().plus(HOLD_RECHECK).truncatedTo(ChronoUnit.SECONDS);
            user.deletionDeferred(next);
            users.save(user);
            schedule(user.getId(), next);
            emails.deletionDeferred(user.getEmail());
            audit.record(null, null, "ACCOUNT_DELETION_DEFERRED", "USER", user.getId(), AuditLog.details("until", next));
            return;
        }
        UUID id = user.getId();
        jdbc.sql("DELETE FROM refresh_token WHERE user_id = :u").param("u", id).update();
        jdbc.sql("DELETE FROM one_time_token WHERE user_id = :u").param("u", id).update();
        jdbc.sql("DELETE FROM password_credential WHERE auth_identity_id IN (SELECT id FROM auth_identity WHERE user_id = :u)")
            .param("u", id).update();
        jdbc.sql("DELETE FROM auth_identity WHERE user_id = :u").param("u", id).update();
        users.anonymize(id);
        audit.record(null, null, "ACCOUNT_ANONYMIZED", "USER", id, null);
    }

    private void schedule(UUID userId, Instant due) {
        jobs.enqueue(JobRequest.of(JOB, JOB + ":" + userId + ":" + due.getEpochSecond(), new AnonymizeWork(userId, due))
            .runAfter(due).withMaxAttempts(10));
    }

    private User user(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException.NotFound("user_not_found", "User not found"));
    }

    private static DeletionStatus status(User u) {
        return new DeletionStatus(u.getId(), u.getStatus().name(), u.getDeletionRequestedAt(), u.getDeletionDueAt(),
            u.getLegalHoldReason() != null, u.getLegalHoldReason(), u.getAnonymizedAt());
    }

    private static void requirePlatformAdmin(CurrentActor actor) {
        if (!actor.isPlatformAdmin()) {
            throw new ApiException.Forbidden("forbidden", "Only platform admins can do this");
        }
    }

    private static void requireContractTeamOrAdmin(CurrentActor actor) {
        if (!actor.isPlatformAdmin() && !actor.isEnrollmentManager()) {
            throw new ApiException.Forbidden("forbidden", "Only the contract team or platform admins can do this");
        }
    }
}
