package org.example.ohara.live.application;

import org.example.ohara.common.web.ApiException;
import org.example.ohara.enrollment.application.OfferingQueries;
import org.example.ohara.live.api.dto.LiveSessionResponse;
import org.example.ohara.live.domain.LiveChannel;
import org.example.ohara.live.domain.LiveSession;
import org.example.ohara.live.infrastructure.persistence.LiveAttendanceRepository;
import org.example.ohara.live.infrastructure.persistence.LiveChannelRepository;
import org.example.ohara.live.infrastructure.persistence.LiveSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Shared session mechanics: ending a class (and settling attendance), channel lookup, and the response view. */
@Component
class LiveLifecycle {

    private final LiveSessionRepository sessions;
    private final LiveChannelRepository channels;
    private final LiveAttendanceRepository attendance;
    private final OfferingQueries offerings;
    private final LiveStreamingProvider provider;
    private final LiveRecordings recordings;
    private final Duration joinEarly;
    private final double presentRatio;

    LiveLifecycle(LiveSessionRepository sessions, LiveChannelRepository channels, LiveAttendanceRepository attendance,
                  OfferingQueries offerings, LiveStreamingProvider provider, LiveRecordings recordings,
                  @Value("${ohara.live.join-early:15m}") Duration joinEarly,
                  @Value("${ohara.live.present-ratio:0.75}") double presentRatio) {
        this.sessions = sessions;
        this.channels = channels;
        this.attendance = attendance;
        this.offerings = offerings;
        this.provider = provider;
        this.recordings = recordings;
        this.joinEarly = joinEarly;
        this.presentRatio = presentRatio;
    }

    /**
     * Ends the class at {@code at}. Every learner with access to the offering gets an attendance row (ABSENT if they
     * never joined) and a status from the time they were credited against the time the class was on air. If the
     * provider already reported the recording, it's registered now.
     */
    void end(LiveSession session, Instant at, boolean stopEncoder) {
        if (stopEncoder) {
            provider.stopStream(channel(session).getChannelRef());
        }
        session.end(at);
        for (UUID learner : offerings.learnersWithAccess(session.getOfferingId())) {
            attendance.ensureExists(session.getId(), learner);
        }
        long aired = session.airedSeconds();
        attendance.findBySessionIdOrderByUserId(session.getId()).forEach(row -> row.decide(aired, presentRatio));
        recordings.registerIfReady(session);
        sessions.save(session);
    }

    LiveChannel channel(LiveSession session) {
        return channels.findById(session.getChannelId()).orElseThrow();
    }

    Instant joinOpensAt(LiveSession session) {
        return session.getScheduledStart().minus(joinEarly);
    }

    LiveSessionResponse view(LiveSession s) {
        return new LiveSessionResponse(s.getId(), s.getOrganizationId(), s.getCohortId(), s.getOfferingId(), s.getLessonId(),
            s.getTitle(), s.getHostUserId(), s.getScheduledStart(), s.getScheduledEnd(), joinOpensAt(s), s.getStatus().name(),
            s.isNeedsHd(), s.getActualStart(), s.getActualEnd(), s.getCancelledReason(), s.getRecordingAssetId());
    }

    static ApiException notFound() {
        return new ApiException.NotFound("live_session_not_found", "Live session not found");
    }
}
