package org.example.ohara.ai.infrastructure.stt;

import org.example.ohara.ai.application.SpeechToText;
import org.example.ohara.content.application.ObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for Amazon Transcribe in development and tests. It doesn't listen to anything: it returns one 10-second
 * segment of a canned algorithms lecture per 10 s of media, after reporting PENDING once (so polling is exercised).
 * Markers in the source bytes change the outcome: FAIL_STT (the job fails), SILENT (no speech), MUMBLE (low confidence).
 */
@Component
@ConditionalOnProperty(name = "ohara.ai.stt.provider", havingValue = "local", matchIfMissing = true)
class LocalSpeechToText implements SpeechToText {

    static final List<String> LECTURE = List.of(
        "Welcome to this lecture on sorting algorithms.",
        "Merge sort divides the array into two halves and sorts each half recursively.",
        "Quick sort picks a pivot and partitions the elements around it.",
        "Binary search finds an element in a sorted array by halving the interval.",
        "The time complexity of merge sort is n log n in every case.",
        "Heap sort builds a binary heap and repeatedly extracts the maximum.");

    private record Job(Request request, String marker, boolean polled) {
    }

    private final ObjectStorage storage;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    LocalSpeechToText(ObjectStorage storage) {
        this.storage = storage;
    }

    @Override
    public String provider() {
        return "LOCAL";
    }

    @Override
    public String model() {
        return "local-stt-1";
    }

    @Override
    public String submit(Request request) {
        String ref = "local-stt-" + UUID.randomUUID();
        jobs.put(ref, new Job(request, marker(request.mediaKey()), false));
        return ref;
    }

    @Override
    public Result status(String jobRef) {
        Job job = jobs.get(jobRef);
        if (job == null) {
            return new Result(State.FAILED, "Unknown job (provider restarted)", null, List.of());
        }
        if (!job.polled()) {
            jobs.put(jobRef, new Job(job.request(), job.marker(), true));
            return new Result(State.PENDING, null, null, List.of());
        }
        return switch (job.marker()) {
            case "FAIL_STT" -> new Result(State.FAILED, "Simulated transcription failure", null, List.of());
            case "SILENT" -> new Result(State.COMPLETE, null, "en-IN", List.of());
            default -> new Result(State.COMPLETE, null, "en-IN", segments(job.request().durationMs(), job.marker().equals("MUMBLE") ? 0.3 : 0.92));
        };
    }

    private static List<Segment> segments(long durationMs, double confidence) {
        List<Segment> segments = new ArrayList<>();
        for (long start = 0, i = 0; start < durationMs; start += 10_000, i++) {
            segments.add(new Segment(start, Math.min(start + 10_000, durationMs), LECTURE.get((int) (i % LECTURE.size())),
                confidence, "spk_0"));
        }
        return segments;
    }

    private String marker(String key) {
        try (InputStream in = storage.open(key)) {
            String head = new String(in.readNBytes(4096), StandardCharsets.ISO_8859_1);
            for (String marker : List.of("FAIL_STT", "SILENT", "MUMBLE")) {
                if (head.contains(marker)) {
                    return marker;
                }
            }
            return "";
        } catch (IOException e) {
            return "";
        }
    }
}
