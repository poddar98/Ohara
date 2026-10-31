package org.example.ohara.live.application;

import org.example.ohara.content.application.AssetService;
import org.example.ohara.content.application.VideoEvents;
import org.example.ohara.live.domain.LiveSession;
import org.example.ohara.live.infrastructure.persistence.LiveSessionRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A class's recording becomes an ordinary video in the organization's library, processed by the same pipeline as
 * uploads (adopted rather than transcoded). The session follows its asset: RECORDING_PROCESSING → RECORDING_READY or
 * RECORDING_FAILED, and back to RECORDING_PROCESSING when staff reprocess a failed one.
 */
@Component
class LiveRecordings {

    private final AssetService assets;
    private final LiveSessionRepository sessions;

    LiveRecordings(AssetService assets, LiveSessionRepository sessions) {
        this.assets = assets;
        this.sessions = sessions;
    }

    /** Once the class has ended and its recording has been reported, in whichever order those happen. */
    void registerIfReady(LiveSession session) {
        if (session.isRecordingRegistrable()) {
            UUID assetId = assets.registerLiveRecording(session.getOrganizationId(), session.getId(),
                "Recording: " + session.getTitle(), session.getRecordingMasterKey(), session.getHostUserId());
            session.recordingRegistered(assetId);
        }
    }

    @EventListener
    void on(VideoEvents.Ready event) {
        sessions.findByRecordingAssetId(event.assetId()).ifPresent(LiveSession::recordingReady);
    }

    @EventListener
    void on(VideoEvents.Failed event) {
        sessions.findByRecordingAssetId(event.assetId()).ifPresent(LiveSession::recordingFailed);
    }

    @EventListener
    void on(VideoEvents.Reprocessing event) {
        sessions.findByRecordingAssetId(event.assetId()).ifPresent(s -> s.recordingRegistered(event.assetId()));
    }
}
