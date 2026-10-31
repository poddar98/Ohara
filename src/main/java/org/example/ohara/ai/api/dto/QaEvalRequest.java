package org.example.ohara.ai.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A labelled question set. Retrieval searches all assets named in the set (like one course); a case is a hit when a
 * retrieved passage of {@code assetId} overlaps [expectedStartMs, expectedEndMs]. {@code withAnswers} also asks the
 * model (costs tokens) and reports the grounded rate.
 */
public record QaEvalRequest(@NotEmpty @Size(max = 500) List<@Valid Case> cases, Boolean withAnswers) {

    public record Case(@NotBlank @Size(max = 500) String question, @NotNull UUID assetId, long expectedStartMs, long expectedEndMs) {
    }
}
