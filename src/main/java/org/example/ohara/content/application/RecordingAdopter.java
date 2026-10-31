package org.example.ohara.content.application;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "adopt" path for live recordings (docs 06 §7.5): the provider already recorded HLS renditions, so instead of
 * transcoding they are copied into the asset's delivery layout ({@code <height>p/index.m3u8}, the same contract the
 * transcoder follows) under a fresh master playlist. The result then goes through the same verification as uploads.
 */
@Component
class RecordingAdopter {

    /** What was found in the recording: its renditions, and its length and size taken from the top rendition. */
    record Recording(List<Rendition> renditions, long durationMs, int width, int height) {
    }

    record Rendition(int width, int height, int bandwidth, String playlistKey, List<String> lines) {
    }

    static class UnusableRecordingException extends Exception {
        UnusableRecordingException(String message) {
            super(message);
        }
    }

    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern BANDWIDTH = Pattern.compile("[:,]BANDWIDTH=(\\d+)");
    private static final Pattern EXTINF = Pattern.compile("^#EXTINF:([0-9.]+)");

    private final ObjectStorage storage;

    RecordingAdopter(ObjectStorage storage) {
        this.storage = storage;
    }

    /** Reads the recording's master playlist and its renditions. */
    Recording inspect(String masterKey) throws UnusableRecordingException {
        List<String> master = lines(masterKey);
        if (master.isEmpty() || !master.getFirst().equals("#EXTM3U")) {
            throw new UnusableRecordingException("Recording master playlist missing or malformed");
        }
        String dir = directory(masterKey);
        List<Rendition> renditions = new ArrayList<>();
        for (int i = 0; i + 1 < master.size(); i++) {
            String line = master.get(i);
            if (!line.startsWith("#EXT-X-STREAM-INF:")) {
                continue;
            }
            Matcher res = RESOLUTION.matcher(line);
            Matcher bw = BANDWIDTH.matcher(line);
            if (!res.find() || !bw.find()) {
                throw new UnusableRecordingException("Rendition without RESOLUTION/BANDWIDTH");
            }
            String playlistKey = dir + master.get(i + 1);
            List<String> playlist = lines(playlistKey);
            if (playlist.isEmpty() || !playlist.contains("#EXT-X-ENDLIST")) {
                throw new UnusableRecordingException("Rendition " + playlistKey + " missing or incomplete");
            }
            renditions.add(new Rendition(Integer.parseInt(res.group(1)), Integer.parseInt(res.group(2)),
                Integer.parseInt(bw.group(1)), playlistKey, playlist));
        }
        if (renditions.isEmpty()) {
            throw new UnusableRecordingException("Recording has no renditions");
        }
        Rendition top = renditions.stream().max((a, b) -> Integer.compare(a.height(), b.height())).orElseThrow();
        double seconds = 0;
        for (String l : top.lines()) {
            Matcher inf = EXTINF.matcher(l);
            if (inf.find()) {
                seconds += Double.parseDouble(inf.group(1));
            }
        }
        if (seconds <= 0) {
            throw new UnusableRecordingException("Recording is empty");
        }
        return new Recording(renditions, Math.round(seconds * 1000), top.width(), top.height());
    }

    /** Copies every rendition (playlist rewritten to plain segment names, segments copied server-side) and writes a master. */
    void adopt(Recording recording, String deliveryPrefix) {
        StringBuilder master = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        for (Rendition r : recording.renditions()) {
            String sourceDir = directory(r.playlistKey());
            String targetDir = deliveryPrefix + r.height() + "p/";
            StringBuilder playlist = new StringBuilder();
            for (String line : r.lines()) {
                if (line.startsWith("#")) {
                    playlist.append(line).append('\n');
                } else {
                    String name = line.substring(line.lastIndexOf('/') + 1);
                    storage.copy(sourceDir + line, targetDir + name);
                    playlist.append(name).append('\n');
                }
            }
            storage.put(targetDir + "index.m3u8", playlist.toString().getBytes(StandardCharsets.UTF_8), "application/vnd.apple.mpegurl");
            master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(r.bandwidth())
                .append(",RESOLUTION=").append(r.width()).append('x').append(r.height()).append('\n')
                .append(r.height()).append("p/index.m3u8\n");
        }
        storage.put(deliveryPrefix + "master.m3u8", master.toString().getBytes(StandardCharsets.UTF_8), "application/vnd.apple.mpegurl");
    }

    private static String directory(String key) {
        return key.substring(0, key.lastIndexOf('/') + 1);
    }

    private List<String> lines(String key) throws UnusableRecordingException {
        if (storage.head(key).isEmpty()) {
            return List.of();
        }
        try (InputStream in = storage.open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        } catch (IOException e) {
            throw new UnusableRecordingException("Can't read " + key + ": " + e.getMessage());
        }
    }
}
