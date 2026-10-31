package org.example.ohara.live.infrastructure.ivs;

import org.example.ohara.live.application.LiveStreamEvents;
import org.example.ohara.live.application.LiveStreamingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

/**
 * Turns IVS EventBridge events into {@link LiveStreamEvents} calls:
 * <ul>
 *   <li>"IVS Stream State Change" Stream Start / Stream End → encoder connected / disconnected (a stream no session
 *       accounts for is stopped);</li>
 *   <li>"IVS Recording State Change" Recording End → the recording's HLS master in the recording bucket.</li>
 * </ul>
 * Other events are acknowledged and ignored. Recordings in another bucket than the source bucket are refused (the
 * recording configuration must point at it, since adoption reads through the storage port).
 */
class IvsEventIntake {

    private static final Logger log = LoggerFactory.getLogger(IvsEventIntake.class);

    private final LiveStreamEvents events;
    private final LiveStreamingProvider provider;
    private final JsonMapper json;
    private final String recordingBucket;

    IvsEventIntake(LiveStreamEvents events, LiveStreamingProvider provider, JsonMapper json, String recordingBucket) {
        this.events = events;
        this.provider = provider;
        this.json = json;
        this.recordingBucket = recordingBucket;
    }

    /** Handles one EventBridge event (an SQS message body). Exceptions mean "retry later". */
    void handle(String body) {
        JsonNode event = json.readTree(body);
        String type = event.path("detail-type").asString("");
        JsonNode detail = event.path("detail");
        Instant at = event.hasNonNull("time") ? Instant.parse(event.get("time").asString()) : Instant.now();
        String channelArn = event.path("resources").path(0).asString("");
        switch (type) {
            case "IVS Stream State Change" -> {
                String name = detail.path("event_name").asString("");
                if (name.equals("Stream Start")) {
                    if (!events.streamStarted(channelArn, at)) {
                        provider.stopStream(channelArn); // nobody scheduled this broadcast
                    }
                } else if (name.equals("Stream End")) {
                    events.streamEnded(channelArn, at);
                }
            }
            case "IVS Recording State Change" -> {
                String status = detail.path("recording_status").asString("");
                if (status.equals("Recording End")) {
                    String bucket = detail.path("recording_s3_bucket_name").asString("");
                    if (!bucket.equals(recordingBucket)) {
                        log.error("IVS recording for {} is in bucket {}, expected {}; ignored", channelArn, bucket, recordingBucket);
                        return;
                    }
                    String prefix = detail.path("recording_s3_key_prefix").asString("");
                    long durationMs = detail.path("recording_duration_ms").asLong(0);
                    events.recordingEnded(channelArn, prefix + "/media/hls/master.m3u8", at.minus(Duration.ofMillis(durationMs)), at);
                } else if (status.endsWith("Failure")) {
                    log.error("IVS recording problem on {}: {} ({})", channelArn, status, detail.path("recording_status_reason").asString(""));
                }
            }
            default -> log.debug("Ignoring event {}", type);
        }
    }
}
