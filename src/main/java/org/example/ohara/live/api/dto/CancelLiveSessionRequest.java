package org.example.ohara.live.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelLiveSessionRequest(@NotBlank @Size(max = 200) String reason) {
}
