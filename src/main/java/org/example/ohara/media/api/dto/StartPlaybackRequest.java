package org.example.ohara.media.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** {@code contentId}: the lesson's VIDEO content item to play. */
public record StartPlaybackRequest(@NotNull UUID lessonId, @NotNull UUID contentId) {
}
