package org.example.ohara.live.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.content.application.AssetQueries;
import org.example.ohara.content.application.CdnSigner;
import org.example.ohara.enrollment.application.ContentAccessService;
import org.example.ohara.enrollment.application.OfferingQueries;
import org.example.ohara.live.api.dto.JoinResponse;
import org.example.ohara.live.api.dto.LiveHeartbeatResponse;
import org.example.ohara.live.api.dto.LiveSessionResponse;
import org.example.ohara.live.api.dto.ReplayResponse;
import org.example.ohara.live.application.LiveStreamingProvider.ChatCapability;
import org.example.ohara.live.domain.LiveAttendance;
import org.example.ohara.live.domain.LiveChannel;
import org.example.ohara.live.domain.LiveSession;
import org.example.ohara.live.infrastructure.persistence.LiveAttendanceRepository;
import org.example.ohara.live.infrastructure.persistence.LiveSessionRepository;
import org.example.ohara.organization.application.OrgAuthorizationService;
import org.example.ohara.organization.application.OrgPermission;
import org.example.ohara.user.application.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Joining and attending live classes. A learner may join when they could study the session's offering right now
 * (the same rule as recorded lessons, {@link ContentAccessService#offeringAccess}); the host and the organization's
 * staff join as moderators. Revocation takes effect at the next rejoin, at most {@code playbackTtl} later.
 */
@Service
public class LiveViewerService {

    enum Role { HOST, MODERATOR, VIEWER }

    /** A replay grant plus the CDN cookies to set on the response. */
    public record Replay(ReplayResponse response, List<CdnSigner.Cookie> cookies) {
    }

    private final LiveSessionRepository sessions;
    private final LiveAttendanceRepository attendance;
    private final LiveLifecycle lifecycle;
    private final LiveStreamingProvider provider;
    private final ContentAccessService access;
    private final OfferingQueries offerings;
    private final OrgAuthorizationService orgAuth;
    private final UserService users;
    private final AssetQueries assets;
    private final Duration playbackTtl;
    private final Duration replayTtl;
    private final Duration heartbeatInterval;

    public LiveViewerService(LiveSessionRepository sessions, LiveAttendanceRepository attendance, LiveLifecycle lifecycle,
                             LiveStreamingProvider provider, ContentAccessService access, OfferingQueries offerings,
                             OrgAuthorizationService orgAuth, UserService users, AssetQueries assets,
                             @Value("${ohara.live.playback-ttl:60m}") Duration playbackTtl,
                             @Value("${ohara.playback.session-ttl:20m}") Duration replayTtl,
                             @Value("${ohara.live.heartbeat-interval:30s}") Duration heartbeatInterval) {
        this.sessions = sessions;
        this.attendance = attendance;
        this.lifecycle = lifecycle;
        this.provider = provider;
        this.access = access;
        this.offerings = offerings;
        this.orgAuth = orgAuth;
        this.users = users;
        this.assets = assets;
        this.playbackTtl = playbackTtl;
        this.replayTtl = replayTtl;
        this.heartbeatInterval = heartbeatInterval;
    }

    /** Sessions of the offerings the caller may study right now, by default from a day ago to 30 days ahead. */
    @Transactional(readOnly = true)
    public List<LiveSessionResponse> mySessions(CurrentActor actor, Instant from, Instant to) {
        List<UUID> offeringIds = offerings.accessibleOfferings(actor.userId());
        if (offeringIds.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        return sessions.findForOfferings(offeringIds, from != null ? from : now.minus(1, ChronoUnit.DAYS),
                to != null ? to : now.plus(30, ChronoUnit.DAYS)).stream()
            .map(lifecycle::view)
            .toList();
    }

    @Transactional
    public JoinResponse join(CurrentActor actor, UUID sessionId) {
        LiveSession session = sessions.findById(sessionId).orElseThrow(LiveLifecycle::notFound);
        Role role = authorize(actor, session);
        if (session.getStatus() == LiveSession.Status.CANCELLED) {
            throw new ApiException.Conflict("session_cancelled", "This session was cancelled");
        }
        if (session.isOver()) {
            throw new ApiException.Conflict("session_ended", "This session has ended");
        }
        Instant now = Instant.now();
        Instant opensAt = lifecycle.joinOpensAt(session);
        if (role == Role.VIEWER && now.isBefore(opensAt)) {
            throw new ApiException.Conflict("session_not_open", "This session opens shortly before it starts")
                .with("opensAt", opensAt);
        }
        if (role == Role.VIEWER) {
            attendance.ensureExists(session.getId(), actor.userId());
            attendance.findForUpdate(session.getId(), actor.userId()).orElseThrow().joined(now);
        }

        LiveChannel channel = lifecycle.channel(session);
        Instant expiresAt = now.plus(playbackTtl);
        LiveStreamingProvider.PlaybackGrant playback = provider.playback(channel.getChannelRef(), actor.userId(), expiresAt);
        Set<ChatCapability> capabilities = role == Role.VIEWER
            ? EnumSet.of(ChatCapability.SEND_MESSAGE)
            : EnumSet.allOf(ChatCapability.class);
        String chatToken = provider.chatToken(channel.getChatRoomRef(), actor.userId(), displayName(actor.userId()),
            capabilities, expiresAt);
        return new JoinResponse(session.getId(), session.getStatus().name(), role.name(), playback.playbackUrl(),
            playback.token(), playback.expiresAt(), channel.getChatRoomRef(), chatToken, expiresAt,
            role == Role.VIEWER ? (int) heartbeatInterval.toSeconds() : null);
    }

    /**
     * The class recording, for the same people who could join it. Learners need current access, so a revoked learner
     * loses replay at the next request (at most {@code replayTtl} later). Not counted as attendance.
     */
    @Transactional(readOnly = true)
    public Replay replay(CurrentActor actor, UUID sessionId) {
        LiveSession session = sessions.findById(sessionId).orElseThrow(LiveLifecycle::notFound);
        authorize(actor, session);
        if (session.getStatus() != LiveSession.Status.RECORDING_READY) {
            throw new ApiException.Conflict("recording_not_ready", "The recording isn't available yet")
                .with("sessionStatus", session.getStatus().name());
        }
        Instant expiresAt = Instant.now().plus(replayTtl);
        AssetQueries.PlaybackGrant grant = assets.playback(session.getRecordingAssetId(), expiresAt)
            .orElseThrow(() -> new ApiException.Conflict("recording_not_ready", "The recording isn't available right now"));
        return new Replay(new ReplayResponse(session.getId(), session.getRecordingAssetId(), grant.masterPlaylistUrl(), expiresAt),
            grant.cookies());
    }

    /** Credits time watched since the previous heartbeat (at most two intervals at once), only while on air. */
    @Transactional
    public LiveHeartbeatResponse heartbeat(CurrentActor actor, UUID sessionId) {
        LiveSession session = sessions.findById(sessionId).orElseThrow(LiveLifecycle::notFound);
        LiveAttendance row = attendance.findForUpdate(sessionId, actor.userId())
            .orElseThrow(() -> new ApiException.Conflict("not_joined", "Join the session first"));
        boolean onAir = session.getStatus() == LiveSession.Status.LIVE && session.getStreamOfflineSince() == null;
        row.heartbeat(Instant.now(), onAir, heartbeatInterval.multipliedBy(2));
        return new LiveHeartbeatResponse(session.getStatus().name(), row.getAttendedSeconds());
    }

    private Role authorize(CurrentActor actor, LiveSession session) {
        if (session.getHostUserId().equals(actor.userId())) {
            return Role.HOST;
        }
        if (orgAuth.allows(actor, session.getOrganizationId(), OrgPermission.VIEW_COHORTS)) {
            return Role.MODERATOR;
        }
        ContentAccessService.Decision decision = access.offeringAccess(actor, session.getOfferingId());
        if (decision.allowed()) {
            return Role.VIEWER;
        }
        throw switch (decision.reason()) {
            case ORGANIZATION_INACTIVE -> new ApiException.Forbidden("organization_inactive", "Your organization's access is paused");
            case NO_ACTIVE_ACCESS -> new ApiException.Forbidden("access_expired", "Your access to this course has ended");
            default -> LiveLifecycle.notFound();
        };
    }

    private String displayName(UUID userId) {
        return users.findById(userId)
            .map(u -> (u.getFirstName() + " " + u.getLastName()).strip())
            .filter(n -> !n.isEmpty())
            .orElse("Participant");
    }
}
