package org.example.ohara.content.api.dto;

import java.net.URI;
import java.time.Instant;

/** PUT the part's bytes to {@code url}; keep the ETag response header for completion. */
public record PresignedPartResponse(int partNumber, URI url, Instant expiresAt) {
}
