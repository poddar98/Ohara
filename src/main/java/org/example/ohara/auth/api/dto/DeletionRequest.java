package org.example.ohara.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/** The current password, so a stolen session alone can't delete the account. */
public record DeletionRequest(@NotBlank String password) {
}
