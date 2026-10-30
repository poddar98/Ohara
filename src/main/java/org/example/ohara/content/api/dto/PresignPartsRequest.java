package org.example.ohara.content.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PresignPartsRequest(@NotEmpty @Size(max = 100) List<Integer> partNumbers) {
}
