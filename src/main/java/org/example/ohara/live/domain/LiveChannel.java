package org.example.ohara.live.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A cohort's provider channel and chat room, created once and reused by every session of that cohort. */
@Entity
@Table(name = "live_channel")
public class LiveChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String channelRef;

    @Column(nullable = false)
    private String ingestEndpoint;

    @Column(nullable = false)
    private String chatRoomRef;

    @Column(nullable = false)
    private boolean hd;

    @Column(nullable = false)
    private Instant createdAt;

    protected LiveChannel() {
    }

    public LiveChannel(UUID cohortId, String provider, String channelRef, String ingestEndpoint, String chatRoomRef, boolean hd) {
        this.cohortId = cohortId;
        this.provider = provider;
        this.channelRef = channelRef;
        this.ingestEndpoint = ingestEndpoint;
        this.chatRoomRef = chatRoomRef;
        this.hd = hd;
        this.createdAt = Instant.now();
    }

    public void typeChanged(boolean hd) {
        this.hd = hd;
    }

    public UUID getId() { return id; }
    public UUID getCohortId() { return cohortId; }
    public String getChannelRef() { return channelRef; }
    public String getIngestEndpoint() { return ingestEndpoint; }
    public String getChatRoomRef() { return chatRoomRef; }
    public boolean isHd() { return hd; }
}
