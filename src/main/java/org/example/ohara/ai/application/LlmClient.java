package org.example.ohara.ai.application;

/**
 * Text-generation port (docs 07 §4). Requests ask for one JSON object matching {@code jsonSchema}; the caller validates
 * the answer itself, since a schema-shaped answer can still be wrong. Synchronous; a batch-API adapter can sit behind
 * the same job step later. Throw {@link Unavailable} for transient provider trouble (the step is retried).
 */
public interface LlmClient {

    /** STANDARD for quality-critical drafts; FAST where evaluation shows a smaller model is as good. */
    enum Tier { STANDARD, FAST }

    enum StopReason { END, MAX_TOKENS, REFUSAL }

    record Request(String task, Tier tier, String system, String user, String jsonSchema, int maxOutputTokens) {
    }

    record Response(String text, String model, int inputTokens, int outputTokens, int cacheReadTokens, StopReason stopReason) {
    }

    class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    String provider();

    Response complete(Request request);
}
