package org.example.ohara.live.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.course.application.CourseQueries;
import org.example.ohara.enrollment.application.OfferingQueries;
import org.example.ohara.enrollment.application.OfferingQueries.OfferingFacts;
import org.example.ohara.live.api.dto.AttendanceRow;
import org.example.ohara.live.api.dto.IngestResponse;
import org.example.ohara.live.api.dto.LiveSessionResponse;
import org.example.ohara.live.api.dto.OverrideAttendanceRequest;
import org.example.ohara.live.api.dto.ScheduleLiveSessionRequest;
import org.example.ohara.live.api.dto.UpdateLiveSessionRequest;
import org.example.ohara.live.domain.LiveAttendance;
import org.example.ohara.live.domain.LiveChannel;
import org.example.ohara.live.domain.LiveSession;
import org.example.ohara.live.infrastructure.persistence.LiveAttendanceRepository;
import org.example.ohara.live.infrastructure.persistence.LiveChannelRepository;
import org.example.ohara.live.infrastructure.persistence.LiveSessionRepository;
import org.example.ohara.organization.application.OrgAuthorizationService;
import org.example.ohara.organization.application.OrgPermission;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduling and running live classes for an organization's cohorts. Org admins manage every session; instructors
 * schedule and run the sessions they host. A host can't be double-booked and a cohort's channel can't carry two
 * sessions at once (both enforced by exclusion constraints).
 */
@Service
public class LiveScheduleService {

    private final LiveSessionRepository sessions;
    private final LiveChannelRepository channels;
    private final LiveAttendanceRepository attendance;
    private final LiveLifecycle lifecycle;
    private final LiveStreamingProvider provider;
    private final OrgAuthorizationService orgAuth;
    private final OfferingQueries offerings;
    private final CourseQueries courses;
    private final UserService users;
    private final Duration maxDuration;

    public LiveScheduleService(LiveSessionRepository sessions, LiveChannelRepository channels, LiveAttendanceRepository attendance,
                               LiveLifecycle lifecycle, LiveStreamingProvider provider, OrgAuthorizationService orgAuth,
                               OfferingQueries offerings, CourseQueries courses, UserService users,
                               @Value("${ohara.live.max-duration:6h}") Duration maxDuration) {
        this.sessions = sessions;
        this.channels = channels;
        this.attendance = attendance;
        this.lifecycle = lifecycle;
        this.provider = provider;
        this.orgAuth = orgAuth;
        this.offerings = offerings;
        this.courses = courses;
        this.users = users;
        this.maxDuration = maxDuration;
    }

    @Transactional
    public LiveSessionResponse schedule(CurrentActor actor, UUID orgId, ScheduleLiveSessionRequest request) {
        orgAuth.require(actor, orgId, OrgPermission.SCHEDULE_LIVE);
        UUID host = request.hostUserId() != null ? request.hostUserId() : actor.userId();
        requireHost(actor, orgId, host);

        OfferingFacts offering = offerings.offering(request.offeringId())
            .filter(o -> orgId.equals(o.organizationId()))
            .orElseThrow(() -> new ApiException.NotFound("offering_not_found", "Offering not found"));
        if (offering.cohortId() == null) {
            throw new ApiException.Unprocessable("offering_has_no_cohort", "Live classes are scheduled for a cohort's offering");
        }
        if (!offering.schedulable()) {
            throw new ApiException.Unprocessable("offering_closed", "This offering is closed");
        }
        requireLessonOf(offering, request.lessonId());
        requireWindow(request.scheduledStart(), request.scheduledEnd());

        boolean hd = Boolean.TRUE.equals(request.needsHd());
        LiveChannel channel = channels.findByCohortId(offering.cohortId()).orElseGet(() -> {
            LiveStreamingProvider.Channel created = provider.createChannel("cohort-" + offering.cohortId(), hd);
            return channels.save(new LiveChannel(offering.cohortId(), provider.name(), created.channelRef(),
                created.ingestEndpoint(), created.chatRoomRef(), hd));
        });
        LiveSession session = new LiveSession(orgId, offering.cohortId(), offering.id(), request.lessonId(), channel.getId(),
            request.title().strip(), host, request.scheduledStart(), request.scheduledEnd(), hd, actor.userId());
        return lifecycle.view(saveChecked(session));
    }

    @Transactional
    public LiveSessionResponse update(CurrentActor actor, UUID orgId, UUID sessionId, UpdateLiveSessionRequest request) {
        LiveSession session = manageable(actor, orgId, sessionId);
        if (!session.isUpcoming()) {
            throw new ApiException.Conflict("session_not_editable", "Only upcoming sessions can be changed");
        }
        UUID host = request.hostUserId() != null ? request.hostUserId() : session.getHostUserId();
        if (!host.equals(session.getHostUserId())) {
            requireHost(actor, orgId, host);
        }
        UUID lessonId = request.lessonId() != null ? request.lessonId() : session.getLessonId();
        if (request.lessonId() != null) {
            requireLessonOf(offerings.offering(session.getOfferingId()).orElseThrow(), lessonId);
        }
        Instant start = request.scheduledStart() != null ? request.scheduledStart() : session.getScheduledStart();
        Instant end = request.scheduledEnd() != null ? request.scheduledEnd() : session.getScheduledEnd();
        if (request.scheduledStart() != null || request.scheduledEnd() != null) {
            requireWindow(start, end);
        }
        session.edit(request.title() != null ? request.title().strip() : session.getTitle(), host, lessonId, start, end,
            request.needsHd() != null ? request.needsHd() : session.isNeedsHd());
        return lifecycle.view(saveChecked(session));
    }

    @Transactional
    public LiveSessionResponse cancel(CurrentActor actor, UUID orgId, UUID sessionId, String reason) {
        LiveSession session = manageable(actor, orgId, sessionId);
        if (!session.isUpcoming()) {
            throw new ApiException.Conflict("session_not_cancellable", "Only upcoming sessions can be cancelled");
        }
        session.cancel(reason.strip());
        return lifecycle.view(session);
    }

    @Transactional(readOnly = true)
    public List<LiveSessionResponse> list(CurrentActor actor, UUID orgId, UUID cohortId, Instant from, Instant to) {
        orgAuth.require(actor, orgId, OrgPermission.VIEW_COHORTS);
        Instant now = Instant.now();
        return sessions.findForOrganization(orgId, cohortId, from != null ? from : now.minus(1, ChronoUnit.DAYS),
                to != null ? to : now.plus(60, ChronoUnit.DAYS)).stream()
            .map(lifecycle::view)
            .toList();
    }

    @Transactional(readOnly = true)
    public LiveSessionResponse get(CurrentActor actor, UUID orgId, UUID sessionId) {
        orgAuth.require(actor, orgId, OrgPermission.VIEW_COHORTS);
        return lifecycle.view(sessions.findByIdAndOrganizationId(sessionId, orgId).orElseThrow(LiveLifecycle::notFound));
    }

    /** Encoder settings for the host (or an org admin setting up for them). Marks the session READY. */
    @Transactional
    public IngestResponse ingest(CurrentActor actor, UUID orgId, UUID sessionId) {
        LiveSession session = manageable(actor, orgId, sessionId);
        if (!session.isUpcoming() && session.getStatus() != LiveSession.Status.LIVE) {
            throw new ApiException.Conflict("session_not_open", "This session is " + session.getStatus().name().toLowerCase());
        }
        LiveChannel channel = lifecycle.channel(session);
        if (session.isUpcoming() && channel.isHd() != session.isNeedsHd()) {
            provider.setChannelType(channel.getChannelRef(), session.isNeedsHd());
            channel.typeChanged(session.isNeedsHd());
        }
        session.prepared();
        return new IngestResponse(channel.getIngestEndpoint(), provider.streamKey(channel.getChannelRef()),
            channel.isHd() ? "STANDARD" : "BASIC");
    }

    /** The host ends the class: the encoder is disconnected and attendance settled. */
    @Transactional
    public LiveSessionResponse end(CurrentActor actor, UUID orgId, UUID sessionId) {
        LiveSession session = manageable(actor, orgId, sessionId);
        if (session.getStatus() != LiveSession.Status.LIVE) {
            throw new ApiException.Conflict("session_not_live", "Only a live session can be ended; cancel an upcoming one instead");
        }
        lifecycle.end(session, Instant.now(), true);
        return lifecycle.view(session);
    }

    @Transactional(readOnly = true)
    public List<AttendanceRow> attendance(CurrentActor actor, UUID orgId, UUID sessionId) {
        orgAuth.require(actor, orgId, OrgPermission.VIEW_COHORTS);
        LiveSession session = sessions.findByIdAndOrganizationId(sessionId, orgId).orElseThrow(LiveLifecycle::notFound);
        List<LiveAttendance> rows = attendance.findBySessionIdOrderByUserId(session.getId());
        Map<UUID, User> people = users.findByIds(rows.stream().map(LiveAttendance::getUserId).toList());
        return rows.stream().map(a -> row(a, people.get(a.getUserId()))).toList();
    }

    /** Corrects a settled status (e.g. a learner followed from a classroom screen). Never recomputed afterwards. */
    @Transactional
    public AttendanceRow overrideAttendance(CurrentActor actor, UUID orgId, UUID sessionId, UUID userId,
                                            OverrideAttendanceRequest request) {
        LiveSession session = manageable(actor, orgId, sessionId);
        if (!session.isOver()) {
            throw new ApiException.Conflict("session_not_ended", "Attendance can be corrected once the session has ended");
        }
        LiveAttendance row = attendance.findForUpdate(session.getId(), userId)
            .orElseThrow(() -> new ApiException.NotFound("attendee_not_found", "No attendance record for this user"));
        row.override(LiveAttendance.Status.valueOf(request.status()), request.note());
        return row(row, users.findById(userId).orElse(null));
    }

    private static AttendanceRow row(LiveAttendance a, User user) {
        String name = user == null ? null : (user.getFirstName() + " " + user.getLastName()).strip();
        return new AttendanceRow(a.getUserId(), user == null ? null : user.getEmail(), name, a.getFirstJoinedAt(),
            a.getLastSeenAt(), a.getJoinCount(), a.getAttendedSeconds(),
            a.getStatus() == null ? null : a.getStatus().name(), a.getSource().name(), a.getNote());
    }

    /** Org admins manage every session; instructors only the ones they host. */
    private LiveSession manageable(CurrentActor actor, UUID orgId, UUID sessionId) {
        orgAuth.require(actor, orgId, OrgPermission.SCHEDULE_LIVE);
        LiveSession session = sessions.findByIdAndOrganizationId(sessionId, orgId).orElseThrow(LiveLifecycle::notFound);
        if (!session.getHostUserId().equals(actor.userId()) && !isManager(actor, orgId)) {
            throw new ApiException.Forbidden("forbidden", "Only the host or an organization admin can manage this session");
        }
        return session;
    }

    private boolean isManager(CurrentActor actor, UUID orgId) {
        return orgAuth.allows(actor, orgId, OrgPermission.MANAGE_COHORTS);
    }

    private void requireHost(CurrentActor actor, UUID orgId, UUID host) {
        if (!host.equals(actor.userId()) && !isManager(actor, orgId)) {
            throw new ApiException.Forbidden("forbidden", "Instructors can only schedule sessions they host");
        }
        if (!orgAuth.userHas(orgId, host, OrgPermission.SCHEDULE_LIVE)) {
            throw new ApiException.Unprocessable("invalid_host", "The host must be an instructor or admin of this organization");
        }
    }

    private void requireLessonOf(OfferingFacts offering, UUID lessonId) {
        if (lessonId != null && !courses.lesson(lessonId).map(l -> l.courseId().equals(offering.courseId())).orElse(false)) {
            throw new ApiException.Unprocessable("lesson_not_in_course", "The lesson isn't part of this offering's course");
        }
    }

    private void requireWindow(Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new ApiException.Unprocessable("invalid_window", "The session must end after it starts");
        }
        if (Duration.between(start, end).compareTo(maxDuration) > 0) {
            throw new ApiException.Unprocessable("session_too_long", "A session can last at most " + maxDuration.toHours() + " hours")
                .with("maxDurationMinutes", maxDuration.toMinutes());
        }
        if (start.isBefore(Instant.now().minus(1, ChronoUnit.MINUTES))) {
            throw new ApiException.Unprocessable("start_in_past", "The session can't start in the past");
        }
    }

    /** Flushes so overlap violations surface here, as a specific 409, rather than at commit. */
    private LiveSession saveChecked(LiveSession session) {
        try {
            return sessions.saveAndFlush(session);
        } catch (DataIntegrityViolationException e) {
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("ex_live_session_host_overlap")) {
                throw new ApiException.Conflict("host_double_booked", "The host already has a session at that time");
            }
            if (detail.contains("ex_live_session_channel_overlap")) {
                throw new ApiException.Conflict("cohort_double_booked", "This cohort already has a session at that time");
            }
            throw e;
        }
    }
}
