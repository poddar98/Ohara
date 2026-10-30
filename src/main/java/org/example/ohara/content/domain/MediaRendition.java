package org.example.ohara.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One verified HLS variant of a processing generation. */
@Entity
@Table(name = "media_rendition")
public class MediaRendition {

    public enum Kind { HLS_VARIANT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "content_asset_id", nullable = false)
    private UUID contentAssetId;

    @Column(nullable = false)
    private int processingGeneration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int bitrateKbps;

    @Column(nullable = false)
    private String playlistKey;

    @Column(nullable = false)
    private int segmentCount;

    @Column(nullable = false)
    private Instant verifiedAt;

    protected MediaRendition() {
    }

    public MediaRendition(UUID contentAssetId, int generation, int height, int width, int bitrateKbps, String playlistKey,
                          int segmentCount) {
        this.contentAssetId = contentAssetId;
        this.processingGeneration = generation;
        this.kind = Kind.HLS_VARIANT;
        this.height = height;
        this.width = width;
        this.bitrateKbps = bitrateKbps;
        this.playlistKey = playlistKey;
        this.segmentCount = segmentCount;
        this.verifiedAt = Instant.now();
    }

    public int getProcessingGeneration() { return processingGeneration; }
    public int getHeight() { return height; }
    public int getWidth() { return width; }
    public int getBitrateKbps() { return bitrateKbps; }
    public String getPlaylistKey() { return playlistKey; }
    public int getSegmentCount() { return segmentCount; }
}
