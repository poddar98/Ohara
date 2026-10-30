package org.example.ohara.live.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OverrideAttendanceRequest(@NotNull @Pattern(regexp = "PRESENT|PARTIAL|ABSENT") String status,
                                        @Size(max = 300) String note) {
}
