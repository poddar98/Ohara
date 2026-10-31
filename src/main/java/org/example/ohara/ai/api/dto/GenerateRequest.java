package org.example.ohara.ai.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code type}: SUMMARY (summary and chapters), QUIZ_DRAFT or FLASHCARDS. {@code instructions} are passed to the model
 * (e.g. "focus on complexity analysis"). {@code force} regenerates for a transcript that already has this output.
 */
public record GenerateRequest(@NotNull @Pattern(regexp = "SUMMARY|QUIZ_DRAFT|FLASHCARDS") String type,
                              @Size(max = 500) String instructions, Boolean force) {
}
