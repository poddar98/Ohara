package org.example.ohara.course.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModuleRequest(@NotBlank @Size(max = 200) String title) {
}
