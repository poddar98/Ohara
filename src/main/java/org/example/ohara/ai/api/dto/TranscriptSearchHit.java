package org.example.ohara.ai.api.dto;

import java.util.UUID;

/** {@code snippet} marks the matched words with &lt;b&gt;…&lt;/b&gt;; {@code startMs} is the deep-link position. */
public record TranscriptSearchHit(UUID lessonId, String lessonTitle, UUID contentId, UUID assetId, long startMs, long endMs,
                                  String snippet) {
}
