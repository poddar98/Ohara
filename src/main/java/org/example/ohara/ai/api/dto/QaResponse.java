package org.example.ohara.ai.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code outcome}: ANSWERED (grounded, with citations), NOT_COVERED (nothing relevant in the learner's videos; no model
 * call), UNSUPPORTED (the model or the citation check found no support), REFUSED, or INVALID. Only ANSWERED is grounded.
 */
public record QaResponse(UUID questionId, String outcome, boolean grounded, String answer, List<Citation> citations) {

    /** A deep link: open {@code contentId} of {@code lessonId} at {@code startMs}. */
    public record Citation(UUID lessonId, String lessonTitle, UUID contentId, UUID assetId, long startMs, long endMs) {
    }
}
