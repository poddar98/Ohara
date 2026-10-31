package org.example.ohara.ai.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** AI state of one asset: latest transcription run (null if never requested), latest generation per task, every artifact version. */
public record AiStatusResponse(UUID assetId, Transcription transcription, List<Generation> generations, List<Artifact> artifacts) {

    /** Latest LLM run per task; usage includes repair calls. */
    public record Generation(UUID id, String task, String status, int transcriptVersion, String promptVersion, String provider,
                             String model, int attempts, int inputTokens, int outputTokens, long costMicroUsd,
                             String failureCode, String failureDetail, Instant createdAt, Instant completedAt) {
    }

    public record Transcription(UUID id, String status, String provider, String model, int attempts, String language,
                                Integer audioSeconds, Long costMicroUsd, String failureCode, String failureDetail,
                                Instant createdAt, Instant completedAt) {
    }

    public record Artifact(UUID id, String type, int artifactVersion, String status, Integer transcriptVersion,
                           String content, Long costMicroUsd, Instant createdAt, UUID reviewedBy, Instant reviewedAt,
                           String reviewNote, UUID editedBy, Instant editedAt) {
    }
}
