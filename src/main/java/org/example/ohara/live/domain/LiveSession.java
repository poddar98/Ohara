package org.example.ohara.live.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One scheduled live class for a cohort's offering (docs 06 §7). SCHEDULED → READY (host fetched ingest settings)
 * → LIVE (the provider reported the stream started) → ENDED; SCHEDULED/READY may be CANCELLED. The RECORDING_*
 * states follow ENDED once the recording is handed to the media pipeline.
 */
@Entity
@Table(name = "live_session")
public class LiveSession {

    public enum Status { SCHEDULED, READY, LIVE, ENDED, CANCELLED, RECORDING_PROCESSING, RECORDING_READY, RECORDING_FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "course_offering_id", nullable = false)
    private UUID offeringId;

    @Column(name = "lesson_id")
    private UUID lessonId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(nullable = false)
    private String title;

    @Column(name = "host_user_id", nullable = false)
    private UUID hostUserId;

    @Column(nullable = false)
    private Instant scheduledStart;

    @Column(nullable = false)
    private Instant scheduledEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.SCHEDULED;

    @Column(nullable = false)
    private boolean needsHd;

    @Column
    private Instant actualStart;

    @Column
    private Instant actualEnd;

    @Column
    private Instant streamOfflineSince;

    @Column
    private String cancelledReason;

    @Column
    private String recordingMasterKey;

    @Column
    private UUID recordingAssetId;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected LiveSession() {
    }

    public LiveSession(UUID organizationId, UUID cohortId, UUID offeringId, UUID lessonId, UUID channelId, String title,
                       UUID hostUserId, Instant scheduledStart, Instant scheduledEnd, boolean needsHd, UUID createdBy) {
        this.organizationId = organizationId;
        this.cohortId = cohortId;
        this.offeringId = offeringId;
        this.lessonId = lessonId;
        this.channelId = channelId;
        this.title = title;
        this.hostUserId = hostUserId;
        this.scheduledStart = scheduledStart;
        this.scheduledEnd = scheduledEnd;
        this.needsHd = needsHd;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /** Not yet started: the plan may still change or be cancelled. */
    public boolean isUpcoming() {
        return status == Status.SCHEDULED || status == Status.READY;
    }

    /** Over (ended, possibly with its recording in progress or done). */
    public boolean isOver() {
        return status == Status.ENDED || status.name().startsWith("RECORDING_");
    }

    public void edit(String title, UUID hostUserId, UUID lessonId, Instant scheduledStart, Instant scheduledEnd, boolean needsHd) {
        this.title = title;
        this.hostUserId = hostUserId;
        this.lessonId = lessonId;
        this.scheduledStart = scheduledStart;
        this.scheduledEnd = scheduledEnd;
        this.needsHd = needsHd;
        this.updatedAt = Instant.now();
    }

    public void cancel(String reason) {
        this.status = Status.CANCELLED;
        this.cancelledReason = reason;
        this.updatedAt = Instant.now();
    }

    public void prepared() {
        if (status == Status.SCHEDULED) {
            status = Status.READY;
            updatedAt = Instant.now();
        }
    }

    public void streamStarted(Instant at) {
        if (status != Status.LIVE) {
            status = Status.LIVE;
            actualStart = at;
        }
        streamOfflineSince = null;
        updatedAt = Instant.now();
    }

    public void streamStopped(Instant at) {
        if (status == Status.LIVE && streamOfflineSince == null) {
            streamOfflineSince = at;
            updatedAt = Instant.now();
        }
    }

    public void end(Instant at) {
        status = Status.ENDED;
        actualEnd = at;
        streamOfflineSince = null;
        updatedAt = Instant.now();
    }

    /** The provider finished writing the recording. The first one reported is kept. */
    public boolean recordingAvailable(String masterKey) {
        if (recordingMasterKey != null) {
            return false;
        }
        recordingMasterKey = masterKey;
        updatedAt = Instant.now();
        return true;
    }

    /** Registration needs both: the class has ended, and the provider has reported its recording. */
    public boolean isRecordingRegistrable() {
        return status == Status.ENDED && recordingMasterKey != null && recordingAssetId == null;
    }

    public void recordingRegistered(UUID assetId) {
        recordingAssetId = assetId;
        status = Status.RECORDING_PROCESSING;
        updatedAt = Instant.now();
    }

    public void recordingReady() {
        status = Status.RECORDING_READY;
        updatedAt = Instant.now();
    }

    public void recordingFailed() {
        status = Status.RECORDING_FAILED;
        updatedAt = Instant.now();
    }

    /** Seconds the class was actually on air (0 if it never started). */
    public long airedSeconds() {
        if (actualStart == null || actualEnd == null || !actualEnd.isAfter(actualStart)) {
            return 0;
        }
        return actualEnd.getEpochSecond() - actualStart.getEpochSecond();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getCohortId() { return cohortId; }
    public UUID getOfferingId() { return offeringId; }
    public UUID getLessonId() { return lessonId; }
    public UUID getChannelId() { return channelId; }
    public String getTitle() { return title; }
    public UUID getHostUserId() { return hostUserId; }
    public Instant getScheduledStart() { return scheduledStart; }
    public Instant getScheduledEnd() { return scheduledEnd; }
    public Status getStatus() { return status; }
    public boolean isNeedsHd() { return needsHd; }
    public Instant getActualStart() { return actualStart; }
    public Instant getActualEnd() { return actualEnd; }
    public Instant getStreamOfflineSince() { return streamOfflineSince; }
    public String getCancelledReason() { return cancelledReason; }
    public UUID getCreatedBy() { return createdBy; }
    public String getRecordingMasterKey() { return recordingMasterKey; }
    public UUID getRecordingAssetId() { return recordingAssetId; }
}
