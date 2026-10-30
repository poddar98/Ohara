package org.example.ohara.course.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Every existing id, in the new order. */
public record OrderRequest(@NotNull List<UUID> ids) {
}
