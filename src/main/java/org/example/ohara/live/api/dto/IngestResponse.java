package org.example.ohara.live.api.dto;

/** Encoder settings for the host. The stream key is a secret: shown on request, never stored or logged by Ohara. */
public record IngestResponse(String ingestEndpoint, String streamKey, String channelType) {
}
