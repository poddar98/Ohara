package org.example.ohara.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LegalHoldRequest(@NotBlank @Size(max = 300) String reason) {
}
