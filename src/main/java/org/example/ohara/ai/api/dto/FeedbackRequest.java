package org.example.ohara.ai.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record FeedbackRequest(@NotNull @Pattern(regexp = "UP|DOWN") String rating) {
}
