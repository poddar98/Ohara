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
 * Checks transcoder output before a video is called READY: the master playlist lists variants, every variant playlist
 * is complete (ENDLIST), its first and last segments exist, and its total duration matches the source.
 * A submitted or even "complete" job is not proof of a playable video.
 */
@Component
class HlsVerifier {

    record Variant(int width, int height, int bandwidthKbps, String playlistKey, int segmentCount) {
    }

    static class VerificationException extends Exception {
        VerificationException(String message) {
            super(message);
        }
    }

    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern BANDWIDTH = Pattern.compile("[:,]BANDWIDTH=(\\d+)");
    private static final Pattern EXTINF = Pattern.compile("^#EXTINF:([0-9.]+)");

    private final ObjectStorage storage;

    HlsVerifier(ObjectStorage storage) {
        this.storage = storage;
    }

    List<Variant> verify(String prefix, long expectedDurationMs, int expectedVariants) throws VerificationException {
        List<String> master = lines(prefix + "master.m3u8");
        if (master.isEmpty() || !master.getFirst().equals("#EXTM3U")) {
            throw new VerificationException("Master playlist missing or malformed");
        }
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < master.size(); i++) {
            String line = master.get(i);
            if (!line.startsWith("#EXT-X-STREAM-INF:") || i + 1 >= master.size()) {
                continue;
            }
            Matcher res = RESOLUTION.matcher(line);
            Matcher bw = BANDWIDTH.matcher(line);
            if (!res.find() || !bw.find()) {
                throw new VerificationException("Variant without RESOLUTION/BANDWIDTH");
            }
            String playlistKey = prefix + master.get(i + 1).trim();
            variants.add(verifyVariant(playlistKey, Integer.parseInt(res.group(1)), Integer.parseInt(res.group(2)),
                Integer.parseInt(bw.group(1)) / 1000, expectedDurationMs));
        }
        if (variants.size() != expectedVariants) {
            throw new VerificationException("Expected " + expectedVariants + " variants, found " + variants.size());
        }
        return variants;
    }

    private Variant verifyVariant(String playlistKey, int width, int height, int bandwidthKbps, long expectedDurationMs)
            throws VerificationException {
        List<String> lines = lines(playlistKey);
        if (lines.isEmpty() || !lines.getFirst().equals("#EXTM3U") || !lines.contains("#EXT-X-ENDLIST")) {
            throw new VerificationException("Variant playlist " + playlistKey + " missing or incomplete");
        }
        String dir = playlistKey.substring(0, playlistKey.lastIndexOf('/') + 1);
        List<String> segments = new ArrayList<>();
        double seconds = 0;
        for (int i = 0; i < lines.size(); i++) {
            Matcher inf = EXTINF.matcher(lines.get(i));
            if (inf.find() && i + 1 < lines.size()) {
                seconds += Double.parseDouble(inf.group(1));
                segments.add(lines.get(i + 1).trim());
            }
        }
        if (segments.isEmpty()) {
            throw new VerificationException("Variant " + playlistKey + " has no segments");
        }
        for (String segment : List.of(segments.getFirst(), segments.getLast())) {
            if (storage.head(dir + segment).isEmpty()) {
                throw new VerificationException("Segment " + dir + segment + " is missing");
            }
        }
        double expected = expectedDurationMs / 1000.0;
        if (Math.abs(seconds - expected) > Math.max(1.0, expected * 0.02)) {
            throw new VerificationException("Variant " + playlistKey + " lasts " + seconds + " s, source " + expected + " s");
        }
        return new Variant(width, height, bandwidthKbps, playlistKey, segments.size());
    }

    private List<String> lines(String key) throws VerificationException {
        if (storage.head(key).isEmpty()) {
            return List.of();
        }
        try (InputStream in = storage.open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        } catch (IOException e) {
            throw new VerificationException("Can't read " + key + ": " + e.getMessage());
        }
    }
}
