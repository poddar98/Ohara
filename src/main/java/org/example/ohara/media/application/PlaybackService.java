package org.example.ohara.media.application;

import org.example.ohara.ai.application.AiQueries;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.content.application.AssetQueries;
import org.example.ohara.content.application.CdnSigner;
import org.example.ohara.enrollment.api.dto.LessonView;
import org.example.ohara.enrollment.application.LearnerService;
import org.example.ohara.media.api.dto.PlaybackResponse;
import org.example.ohara.media.domain.PlaybackSession;
import org.example.ohara.media.infrastructure.persistence.PlaybackSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Playback authorization (docs 06 §6): authentication (the caller), entitlement (the same lesson check every other
 * learner endpoint uses), then delivery authorization (short-lived signed cookies scoped to this one video).
 * Revoked access stops playback at the next refresh, at most {@code ttl} later.
 */
@Service
public class PlaybackService {

    /** A started or refreshed session plus the cookies to set on the response. */
    public record Issued(PlaybackResponse response, List<CdnSigner.Cookie> cookies) {
    }

    private final LearnerService learner;
    private final AssetQueries assets;
    private final PlaybackSessionRepository sessions;
    private final AiQueries ai;
    private final Duration ttl;
    private final Duration refreshMargin;

    public PlaybackService(LearnerService learner, AssetQueries assets, PlaybackSessionRepository sessions, AiQueries ai,
                           @Value("${ohara.playback.session-ttl:20m}") Duration ttl,
                           @Value("${ohara.playback.refresh-margin:5m}") Duration refreshMargin) {
        this.learner = learner;
        this.assets = assets;
        this.sessions = sessions;
        this.ai = ai;
        this.ttl = ttl;
        this.refreshMargin = refreshMargin;
    }

    @Transactional
    public Issued start(CurrentActor actor, UUID lessonId, UUID contentId) {
        LessonView lesson = learner.lesson(actor, lessonId);
        LessonView.ContentItem video = videoItem(lesson, contentId);
        Instant expiresAt = expiry(lesson);
        AssetQueries.PlaybackGrant grant = grant(video, expiresAt);
        PlaybackSession session = sessions.save(new PlaybackSession(actor.userId(), lessonId, contentId, video.assetId(),
            lesson.enrollmentId(), expiresAt));
        return issued(session, lesson, grant);
    }

    /** Re-runs the access check; a learner whose access ended gets the same 403 as opening the lesson. */
    @Transactional
    public Issued refresh(CurrentActor actor, UUID sessionId) {
        PlaybackSession session = sessions.findByIdAndUserId(sessionId, actor.userId())
            .orElseThrow(() -> new ApiException.NotFound("session_not_found", "Playback session not found"));
        LessonView lesson = learner.lesson(actor, session.getLessonId());
        LessonView.ContentItem video = videoItem(lesson, session.getContentId());
        Instant expiresAt = expiry(lesson);
        AssetQueries.PlaybackGrant grant = grant(video, expiresAt);
        session.refreshed(expiresAt);
        return issued(session, lesson, grant);
    }

    private static LessonView.ContentItem videoItem(LessonView lesson, UUID contentId) {
        return lesson.contents().stream()
            .filter(c -> c.id().equals(contentId) && "VIDEO".equals(c.kind()))
            .findFirst()
            .orElseThrow(() -> new ApiException.NotFound("content_not_found", "No such video in this lesson"));
    }

    private AssetQueries.PlaybackGrant grant(LessonView.ContentItem video, Instant expiresAt) {
        if (!video.available()) {
            throw new ApiException.Conflict("media_not_ready", "This video isn't available right now");
        }
        return assets.playback(video.assetId(), expiresAt)
            .orElseThrow(() -> new ApiException.Conflict("media_not_ready", "This video isn't available right now"));
    }

    /** Never longer than the learner's access. */
    private Instant expiry(LessonView lesson) {
        Instant byTtl = Instant.now().plus(ttl);
        return lesson.accessUntil() != null && lesson.accessUntil().isBefore(byTtl) ? lesson.accessUntil() : byTtl;
    }

    private Issued issued(PlaybackSession session, LessonView lesson, AssetQueries.PlaybackGrant grant) {
        Instant refreshAt = session.getExpiresAt().minus(refreshMargin);
        if (refreshAt.isBefore(Instant.now())) {
            refreshAt = Instant.now();
        }
        return new Issued(new PlaybackResponse(session.getId(), grant.masterPlaylistUrl(), session.getExpiresAt(), refreshAt,
            lesson.resumePositionMs(), lesson.enrollmentId(), lesson.lessonKey(),
            ai.captions(session.getAssetId()).stream().map(c -> new PlaybackResponse.Caption(c.language(), c.url())).toList()),
            grant.cookies());
    }
}
