package org.example.ohara.content.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes video processing outcomes. Listeners (live recordings) run synchronously in the same transaction as the
 * asset's state change, so the two never disagree.
 */
@Component
public class VideoEvents {

    /** The video is playable. */
    public record Ready(UUID assetId) {
    }

    /** Processing gave up, or the file was rejected; {@code code} says why. */
    public record Failed(UUID assetId, String code) {
    }

    /** A failed video was queued for processing again. */
    public record Reprocessing(UUID assetId) {
    }

    private final ApplicationEventPublisher publisher;

    VideoEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    void ready(UUID assetId) {
        publisher.publishEvent(new Ready(assetId));
    }

    void failed(UUID assetId, String code) {
        publisher.publishEvent(new Failed(assetId, code));
    }

    void reprocessing(UUID assetId) {
        publisher.publishEvent(new Reprocessing(assetId));
    }
}
