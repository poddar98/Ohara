package org.example.ohara.ai.infrastructure.aws;

import org.example.ohara.ai.application.SpeechToText.Segment;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns Transcribe's word-level output ({@code results.items}) into caption-sized segments. Segments become WebVTT
 * cues and the units of search and Q&A citations, so they're cut where a reader expects: at a speaker change, at a
 * pause of {@value #PAUSE_MS} ms or more, after a sentence once it has run {@value #MIN_SENTENCE_MS} ms, and in any
 * case before {@value #MAX_MS} ms or {@value #MAX_CHARS} characters. A segment's confidence is the mean of its words'.
 * <p>
 * Speaker labels are read from each item ({@code speaker_label}) or, in the older layout, from
 * {@code results.speaker_labels.segments[].items[]} matched by start time.
 */
final class TranscribeOutput {

    static final long PAUSE_MS = 1_500;
    static final long MIN_SENTENCE_MS = 1_500;
    static final long MAX_MS = 8_000;
    static final int MAX_CHARS = 120;

    private TranscribeOutput() {
    }

    private static final class Builder {
        final StringBuilder text = new StringBuilder();
        long start = -1;
        long end;
        double confidenceSum;
        int words;
        String speaker;

        boolean isEmpty() {
            return words == 0;
        }

        Segment build() {
            return new Segment(start, end, text.toString().strip(), words == 0 ? null : confidenceSum / words, speaker);
        }
    }

    static List<Segment> segments(JsonNode root) {
        JsonNode results = root.path("results");
        Map<String, String> speakerByStart = olderSpeakerLabels(results.path("speaker_labels"));
        List<Segment> segments = new ArrayList<>();
        Builder current = new Builder();
        for (JsonNode item : results.path("items")) {
            JsonNode best = item.path("alternatives").path(0);
            String content = best.path("content").asString("");
            if (content.isEmpty()) {
                continue;
            }
            if (item.path("type").asString("").equals("punctuation")) {
                if (current.isEmpty()) {
                    continue;
                }
                current.text.append(content);
                boolean sentenceEnd = content.equals(".") || content.equals("?") || content.equals("!");
                if (sentenceEnd && current.end - current.start >= MIN_SENTENCE_MS) {
                    segments.add(current.build());
                    current = new Builder();
                }
                continue;
            }
            long start = millis(item.path("start_time"));
            long end = millis(item.path("end_time"));
            String startText = item.path("start_time").asString("");
            String speaker = item.hasNonNull("speaker_label") ? item.path("speaker_label").asString()
                : speakerByStart.get(startText);

            if (!current.isEmpty()) {
                boolean newSpeaker = speaker != null && current.speaker != null && !speaker.equals(current.speaker);
                boolean pause = start - current.end >= PAUSE_MS;
                boolean tooLong = end - current.start > MAX_MS || current.text.length() + 1 + content.length() > MAX_CHARS;
                if (newSpeaker || pause || tooLong) {
                    segments.add(current.build());
                    current = new Builder();
                }
            }
            if (current.isEmpty()) {
                current.start = start;
                current.speaker = speaker;
            } else {
                current.text.append(' ');
            }
            current.text.append(content);
            current.end = end;
            current.confidenceSum += Double.parseDouble(best.path("confidence").asString("0"));
            current.words++;
        }
        if (!current.isEmpty()) {
            segments.add(current.build());
        }
        return segments;
    }

    private static Map<String, String> olderSpeakerLabels(JsonNode speakerLabels) {
        Map<String, String> byStart = new HashMap<>();
        for (JsonNode segment : speakerLabels.path("segments")) {
            for (JsonNode item : segment.path("items")) {
                byStart.put(item.path("start_time").asString(""), item.path("speaker_label").asString(null));
            }
        }
        return byStart;
    }

    /** Transcribe writes times and confidences as decimal strings ("12.34"). */
    private static long millis(JsonNode seconds) {
        return Math.round(Double.parseDouble(seconds.asString("0")) * 1000);
    }
}
