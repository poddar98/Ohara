package org.example.ohara.content.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CompleteUploadRequest(@NotEmpty @Valid List<Part> parts) {

    public record Part(@Positive int partNumber, @NotBlank String etag) {
    }
}
