package org.example.ohara.ai.api.dto;

import java.util.List;
import java.util.UUID;

/** {@code status} is the transcript artifact's review status (learners only ever get APPROVED ones). */
public record TranscriptResponse(UUID assetId, int transcriptVersion, String language, String status, List<Segment> segments) {

    public record Segment(int seq, long startMs, long endMs, String speaker, String text, Double confidence) {
    }
}
