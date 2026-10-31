package org.example.ohara.ai.api.dto;

import java.util.List;

/** {@code rank}: 1-based position of the first passage that hits, null if none in the top {@code k}. */
public record QaEvalResponse(int cases, int k, double recallAtK, Double groundedRate, List<CaseResult> results) {

    public record CaseResult(String question, boolean hit, Integer rank, String outcome) {
    }
}
