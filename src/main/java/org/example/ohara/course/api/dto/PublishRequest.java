package org.example.ohara.course.api.dto;

import jakarta.validation.constraints.Size;

public record PublishRequest(@Size(max = 5000) String changeNotes) {
}
