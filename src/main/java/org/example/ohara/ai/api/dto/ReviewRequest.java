package org.example.ohara.ai.api.dto;

import jakarta.validation.constraints.Size;

public record ReviewRequest(@Size(max = 500) String note) {
}
