package org.example.ohara.content.infrastructure.transcoder;

import org.example.ohara.content.application.Ladder;
import org.example.ohara.content.application.ObjectStorage;
import org.example.ohara.content.application.Transcoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for MediaConvert in development and tests. It doesn't decode video: probe recognises MP4/MOV ("ftyp")
 * and Matroska/WebM headers and reports the configured duration and size; submit writes real HLS playlists and
 * placeholder segments synchronously, so the rest of the pipeline (verification, playback) runs end to end.
 * Markers in the source bytes inject failures: FAIL_TRANSCODE (job fails), BAD_OUTPUT (a variant is missing).
 */
@Component
@ConditionalOnProperty(name = "ohara.media.transcoder", havingValue = "local", matchIfMissing = true)
class LocalTranscoder implements Transcoder {

    static final int SEGMENT_SECONDS = 6;

    private final ObjectStorage storage;
    private final long durationMs;
    private final int width;
    private final int height;
    private final Map<String, JobStatus> jobs = new ConcurrentHashMap<>();

    LocalTranscoder(ObjectStorage storage,
                    @Value("${ohara.media.local.duration-ms:30000}") long durationMs,
                    @Value("${ohara.media.local.width:1920}") int width,
                    @Value("${ohara.media.local.height:1080}") int height) {
        this.storage = storage;
        this.durationMs = durationMs;
        this.width = width;
        this.height = height;
    }

    @Override
    public MediaInfo probe(String sourceKey) {
        byte[] head = head(sourceKey);
        String text = new String(head, StandardCharsets.ISO_8859_1);
        boolean mp4 = text.contains("ftyp");
        boolean matroska = head.length >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
            && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
        if (!mp4 && !matroska) {
            return MediaInfo.invalid("Not a recognised video container");
        }
        return new MediaInfo(true, null, durationMs, width, height);
    }

    @Override
    public String submit(Job job) {
        String ref = "local-" + UUID.randomUUID();
        String source = new String(head(job.sourceKey()), StandardCharsets.ISO_8859_1);
        if (source.contains("FAIL_TRANSCODE")) {
            jobs.put(ref, new JobStatus(State.FAILED, "Simulated transcoder failure"));
            return ref;
        }
        StringBuilder master = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        boolean skippedOne = false;
        for (Ladder.Rung rung : job.ladder()) {
            master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(rung.bandwidthKbps() * 1000)
                .append(",AVERAGE-BANDWIDTH=").append((rung.videoKbps() + rung.audioKbps()) * 1000)
                .append(",RESOLUTION=").append(rung.width()).append('x').append(rung.height())
                .append(",CODECS=\"avc1.64001f,mp4a.40.2\"\n")
                .append(rung.name()).append("/index.m3u8\n");
            if (source.contains("BAD_OUTPUT") && !skippedOne) {
                skippedOne = true;
                continue;
            }
            writeVariant(job.outputPrefix() + rung.name() + "/", job.durationMs());
        }
        storage.put(job.outputPrefix() + "master.m3u8", master.toString().getBytes(StandardCharsets.UTF_8),
            "application/vnd.apple.mpegurl");
        jobs.put(ref, new JobStatus(State.COMPLETE, null));
        return ref;
    }

    @Override
    public JobStatus status(String jobRef) {
        return jobs.getOrDefault(jobRef, new JobStatus(State.FAILED, "Unknown job (transcoder restarted)"));
    }

    private void writeVariant(String prefix, long durationMs) {
        long full = durationMs / (SEGMENT_SECONDS * 1000L);
        long remainderMs = durationMs % (SEGMENT_SECONDS * 1000L);
        StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:")
            .append(SEGMENT_SECONDS).append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        int index = 0;
        for (long i = 0; i < full; i++) {
            index++;
            playlist.append("#EXTINF:").append(SEGMENT_SECONDS).append(".000,\n").append(segment(index)).append('\n');
            storage.put(prefix + segment(index), placeholder(index), "video/mp2t");
        }
        if (remainderMs > 0) {
            index++;
            playlist.append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.3f", remainderMs / 1000.0)).append(",\n")
                .append(segment(index)).append('\n');
            storage.put(prefix + segment(index), placeholder(index), "video/mp2t");
        }
        playlist.append("#EXT-X-ENDLIST\n");
        storage.put(prefix + "index.m3u8", playlist.toString().getBytes(StandardCharsets.UTF_8), "application/vnd.apple.mpegurl");
    }

    private static String segment(int index) {
        return String.format("seg_%05d.ts", index);
    }

    private static byte[] placeholder(int index) {
        return ("segment " + index).getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] head(String key) {
        try (InputStream in = storage.open(key)) {
            return in.readNBytes(256);
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
