package org.example.ohara.content.api.dto;

import org.example.ohara.content.domain.ContentAsset;

import java.time.Instant;
import java.util.UUID;

public record AssetResponse(UUID id, UUID ownerOrganizationId, ContentAsset.Type type, String title, ContentAsset.Status status,
                            String fileName, String mimeType, Long bytes, ContentAsset.ContentProfile contentProfile,
                            Long durationMs, Integer width, Integer height, String failureCode, String failureDetail,
                            Instant readyAt, Instant createdAt) {

    public static AssetResponse of(ContentAsset a) {
        return new AssetResponse(a.getId(), a.getOwnerOrganizationId(), a.getType(), a.getTitle(), a.getStatus(),
            a.getOriginalFilename(), a.getMimeType(), a.getSourceBytes(), a.getContentProfile(), a.getDurationMs(),
            a.getWidth(), a.getHeight(), a.getFailureCode(), a.getFailureDetail(), a.getReadyAt(), a.getCreatedAt());
    }
}
