package org.example.ohara.ai.application;

import java.util.List;

/**
 * Speech-to-text port, shaped like Amazon Transcribe batch jobs: submit a stored media file, poll, read timed segments.
 * Adapters must be safe to poll repeatedly and must not charge twice for the same job reference.
 */
public interface SpeechToText {

    /**
     * {@code languages}: candidates for identification, most likely first (e.g. en-IN, hi-IN). {@code clientRef} is
     * stable for one attempt of one transcription: submitting the same {@code clientRef} twice (a crash between the
     * provider call and recording its reference) must not start, or bill, a second provider job.
     */
    record Request(String clientRef, String mediaKey, long durationMs, List<String> languages) {
    }

    record Segment(long startMs, long endMs, String text, Double confidence, String speaker) {
    }

    enum State { PENDING, COMPLETE, FAILED }

    /** {@code segments} and {@code language} are set when COMPLETE; {@code failure} when FAILED. */
    record Result(State state, String failure, String language, List<Segment> segments) {
    }

    String provider();

    String model();

    /** Starts a job; returns the provider's job reference. */
    String submit(Request request);

    Result status(String jobRef);
}
