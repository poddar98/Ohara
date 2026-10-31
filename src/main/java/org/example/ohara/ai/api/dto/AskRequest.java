package org.example.ohara.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** {@code lessonId} narrows the question to one lesson's videos. */
public record AskRequest(@NotBlank @Size(min = 3, max = 500) String question, UUID lessonId) {
}
