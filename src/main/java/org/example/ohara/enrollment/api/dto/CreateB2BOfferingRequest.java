package org.example.ohara.enrollment.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** {@code pinnedVersionId} locks the syllabus to one published version; otherwise each learner gets the latest at enrollment. */
public record CreateB2BOfferingRequest(@NotNull UUID courseId, UUID cohortId, @Size(max = 200) String title,
                                       Instant startsAt, Instant endsAt, UUID pinnedVersionId) {
}
