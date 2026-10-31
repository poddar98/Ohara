package org.example.ohara.live.application;

import org.example.ohara.enrollment.application.OfferingQueries;
import org.example.ohara.live.domain.LiveSession;
import org.example.ohara.live.infrastructure.persistence.LiveSessionRepository;
import org.example.ohara.notification.application.EmailMessage;
import org.example.ohara.notification.application.EmailOutbox;
import org.example.ohara.platform.jobs.JobHandler;
import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Emails learners about their classes: a reminder {@code reminderLead} before the start (a durable job scheduled with
 * the session; rescheduling queues a new one and the stale one finds the start changed and does nothing), and a
 * notice when a class is cancelled. Recipients are the learners who may study the offering when the email is sent.
 */
@Component
class LiveNotifications implements JobHandler<LiveNotifications.ReminderWork> {

    static final String REMINDER_JOB = "LIVE_REMINDER";

    record ReminderWork(UUID sessionId, Instant scheduledStart) {
    }

    private final LiveSessionRepository sessions;
    private final OfferingQueries offerings;
    private final UserService users;
    private final EmailOutbox outbox;
    private final JobScheduler jobs;
    private final Duration reminderLead;
    private final DateTimeFormatter format;

    LiveNotifications(LiveSessionRepository sessions, OfferingQueries offerings, UserService users, EmailOutbox outbox,
                      JobScheduler jobs, @Value("${ohara.live.reminder-lead:1h}") Duration reminderLead,
                      @Value("${ohara.live.display-zone:Asia/Kolkata}") String displayZone) {
        this.sessions = sessions;
        this.offerings = offerings;
        this.users = users;
        this.outbox = outbox;
        this.jobs = jobs;
        this.reminderLead = reminderLead;
        this.format = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a z").withZone(ZoneId.of(displayZone));
    }

    /** Queues the reminder for the session's current start (immediately if the class is closer than the lead). */
    void scheduled(LiveSession session) {
        Instant at = session.getScheduledStart().minus(reminderLead);
        jobs.enqueue(JobRequest.of(REMINDER_JOB, REMINDER_JOB + ":" + session.getId() + ":" + session.getScheduledStart().getEpochSecond(),
                new ReminderWork(session.getId(), session.getScheduledStart()))
            .runAfter(at.isAfter(Instant.now()) ? at : null));
    }

    void cancelled(LiveSession session) {
        notifyLearners(session, "cancelled", "Cancelled: " + session.getTitle(),
            "The live class \"%s\" scheduled for %s has been cancelled.\nReason: %s"
                .formatted(session.getTitle(), format.format(session.getScheduledStart()), session.getCancelledReason()));
    }

    @Override
    public String jobType() {
        return REMINDER_JOB;
    }

    @Override
    public Class<ReminderWork> payloadType() {
        return ReminderWork.class;
    }

    @Override
    @Transactional
    public void handle(ReminderWork work) {
        sessions.findById(work.sessionId())
            .filter(s -> s.isUpcoming() && s.getScheduledStart().equals(work.scheduledStart()))
            .ifPresent(s -> notifyLearners(s, "reminder:" + s.getScheduledStart().getEpochSecond(), "Starting soon: " + s.getTitle(),
                "Your live class \"%s\" starts at %s.\nYou can join from 15 minutes before the start in Ohara."
                    .formatted(s.getTitle(), format.format(s.getScheduledStart()))));
    }

    private void notifyLearners(LiveSession session, String kind, String subject, String body) {
        Map<UUID, User> learners = users.findByIds(offerings.learnersWithAccess(session.getOfferingId()));
        for (User learner : learners.values()) {
            outbox.enqueue(new EmailMessage(learner.getEmail(), subject, body),
                "live:" + session.getId() + ":" + kind + ":" + learner.getId());
        }
    }
}
