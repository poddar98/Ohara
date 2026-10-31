package org.example.ohara.ai.api.dto;

import java.util.List;
import java.util.UUID;

/** Approved study material for one video; absent parts are null or empty. Quizzes aren't here (assessment module, Q-12). */
public record StudyResponse(UUID assetId, Summary summary, List<Chapter> chapters, List<Card> flashcards) {

    public record Summary(String shortSummary, String detailedSummary) {
    }

    public record Chapter(long startMs, String title) {
    }

    public record Card(String front, String back, long sourceStartMs) {
    }
}
