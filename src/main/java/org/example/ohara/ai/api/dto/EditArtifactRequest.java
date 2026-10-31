package org.example.ohara.ai.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/** The whole new body of a draft, in the same shape as its {@code content}; validated like model output. */
public record EditArtifactRequest(@NotNull Map<String, Object> content) {
}
