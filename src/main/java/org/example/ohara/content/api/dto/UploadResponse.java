package org.example.ohara.content.api.dto;

import org.example.ohara.content.domain.MediaUpload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code uploadedParts} lets a client resume an interrupted upload. */
public record UploadResponse(UUID uploadId, MediaUpload.Status status, long partSizeBytes, int partCount, Instant expiresAt,
                             List<Part> uploadedParts, AssetResponse asset) {

    public record Part(int partNumber, long size, String etag) {
    }
}
