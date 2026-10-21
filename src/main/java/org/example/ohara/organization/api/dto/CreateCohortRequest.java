package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateCohortRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$", message = "Code must be 1-64 letters, digits, '.', '_' or '-'")
    String code,
    LocalDate startsOn,
    LocalDate endsOn
) {
}
