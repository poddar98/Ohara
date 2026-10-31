package org.example.ohara.ai.application;

import org.example.ohara.ai.infrastructure.persistence.AiStore;
import org.example.ohara.content.application.AssetQueries.MediaSource;
import org.example.ohara.content.application.ObjectStorage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * WebVTT captions from transcript segments, stored next to the video's HLS files (inside the path its playback cookies
 * cover) and offered to players as a sidecar track. A reprocessed video gets new delivery paths, so the approved
 * captions are rewritten there from the stored transcript, with no new transcription.
 */
@Component
class Captions {

    private final ObjectStorage storage;
    private final AiStore store;

    Captions(ObjectStorage storage, AiStore store) {
        this.storage = storage;
        this.store = store;
    }

    String write(MediaSource source, int transcriptVersion, String language, List<AiStore.SegmentRow> segments) {
        String key = source.deliveryPrefix() + "captions/" + language + "-t" + transcriptVersion + ".vtt";
        storage.put(key, vtt(segments).getBytes(StandardCharsets.UTF_8), "text/vtt");
        return key;
    }

    /** Makes sure the approved captions live under the video's current delivery path. */
    void ensureCurrent(MediaSource source) {
        store.approved(source.assetId(), "CAPTIONS").ifPresent(captions -> {
            if (captions.objectKey() == null || captions.objectKey().startsWith(source.deliveryPrefix())) {
                return;
            }
            String file = captions.objectKey().substring(captions.objectKey().lastIndexOf('/') + 1);
            String key = source.deliveryPrefix() + "captions/" + file;
            storage.put(key, vtt(store.segments(source.assetId(), captions.transcriptVersion())).getBytes(StandardCharsets.UTF_8),
                "text/vtt");
            store.moveObject(captions.id(), key);
        });
    }

    static String vtt(List<AiStore.SegmentRow> segments) {
        StringBuilder out = new StringBuilder("WEBVTT\n");
        for (AiStore.SegmentRow s : segments) {
            out.append('\n').append(s.seq()).append('\n')
                .append(timestamp(s.startMs())).append(" --> ").append(timestamp(s.endMs())).append('\n')
                .append(s.text().replace("-->", "->")).append('\n');
        }
        return out.toString();
    }

    private static String timestamp(long ms) {
        return "%02d:%02d:%02d.%03d".formatted(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }
}
