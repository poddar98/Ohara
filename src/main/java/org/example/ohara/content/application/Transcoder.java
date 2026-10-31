package org.example.ohara.content.application;

import java.util.List;

/**
 * Transcoding port, shaped like AWS MediaConvert: probe the source, submit an asynchronous job, poll its state.
 * Output contract (every adapter): {@code outputPrefix + "master.m3u8"} listing one variant per rung (with RESOLUTION
 * and BANDWIDTH), each variant playlist and its segments under {@code outputPrefix}, referenced by relative URIs.
 * File names below that are the adapter's choice (local: {@code <height>p/index.m3u8}; MediaConvert:
 * {@code master_<height>p.m3u8}); verification follows the master playlist.
 */
public interface Transcoder {

    /** {@code valid} false means the file isn't a usable video (reason says why). */
    record MediaInfo(boolean valid, String reason, long durationMs, int width, int height) {

        public static MediaInfo invalid(String reason) {
            return new MediaInfo(false, reason, 0, 0, 0);
        }
    }

    record Job(String sourceKey, String outputPrefix, List<Ladder.Rung> ladder, long durationMs) {
    }

    enum State { PENDING, COMPLETE, FAILED }

    record JobStatus(State state, String failure) {
    }

    MediaInfo probe(String sourceKey);

    /** Starts a job; returns the provider's job reference. */
    String submit(Job job);

    JobStatus status(String jobRef);
}
