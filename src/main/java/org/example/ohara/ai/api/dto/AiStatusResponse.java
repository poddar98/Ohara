package org.example.ohara.ai.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** AI state of one asset: its latest transcription run (null if never requested) and every artifact version. */
public record AiStatusResponse(UUID assetId, Transcription transcription, List<Artifact> artifacts) {

    public record Transcription(UUID id, String status, String provider, String model, int attempts, String language,
                                Integer audioSeconds, Long costMicroUsd, String failureCode, String failureDetail,
                                Instant createdAt, Instant completedAt) {
    }

    public record Artifact(UUID id, String type, int artifactVersion, String status, Integer transcriptVersion,
                           String content, Long costMicroUsd, Instant createdAt, UUID reviewedBy, Instant reviewedAt,
                           String reviewNote) {
    }
}
