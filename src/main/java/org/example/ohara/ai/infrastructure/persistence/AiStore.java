package org.example.ohara.ai.infrastructure.persistence;

import org.example.ohara.ai.application.SpeechToText;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for transcriptions, artifacts and transcript segments. Plain SQL: segments are bulk-inserted and
 * searched with full-text search, and artifact content is JSONB. Search methods require the allowed-asset list:
 * there is deliberately no way to search across all assets.
 */
@Repository
public class AiStore {

    public record TranscriptionRow(UUID id, UUID assetId, String sourceRef, String status, String provider, String model,
                                   String providerJobRef, int attempts, String language, Integer audioSeconds,
                                   Long costMicroUsd, String failureCode, String failureDetail, Instant createdAt,
                                   Instant completedAt) {
    }

    public record ArtifactRow(UUID id, UUID assetId, String type, String sourceRef, Integer transcriptVersion,
                              int artifactVersion, String status, String content, String objectKey, String provider,
                              String model, Integer audioSeconds, Long costMicroUsd, UUID reviewedBy, Instant reviewedAt,
                              String reviewNote, Instant createdAt, UUID editedBy, Instant editedAt) {
    }

    public record GenerationRow(UUID id, UUID assetId, String task, int transcriptVersion, String promptVersion, String status,
                                String provider, String model, int attempts, int inputTokens, int outputTokens,
                                int cacheReadTokens, long costMicroUsd, String instructions, String failureCode,
                                String failureDetail, Instant createdAt, Instant completedAt) {
    }

    public record SegmentRow(int seq, long startMs, long endMs, String speaker, String text, Double confidence) {
    }

    /** A retrieved segment of an approved transcript. */
    public record Passage(UUID assetId, int transcriptVersion, int seq, long startMs, long endMs, String text, double rank) {
    }

    public record SearchHit(UUID assetId, long startMs, long endMs, String snippet, double rank) {
    }

    private final JdbcClient jdbc;

    AiStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- transcriptions ------------------------------------------------------------------------------

    /** Empty when a run is already in flight for the asset. */
    public Optional<UUID> insertTranscription(UUID assetId, String sourceRef, String provider, String model, UUID requestedBy) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO transcription (id, content_asset_id, source_ref, provider, model, requested_by)
                VALUES (:id, :asset, :ref, :provider, :model, :by)
                ON CONFLICT (content_asset_id) WHERE status = 'SUBMITTED' DO NOTHING""")
            .param("id", id).param("asset", assetId).param("ref", sourceRef).param("provider", provider)
            .param("model", model).param("by", requestedBy)
            .update();
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    public Optional<TranscriptionRow> transcription(UUID id) {
        return jdbc.sql("SELECT * FROM transcription WHERE id = :id").param("id", id).query(AiStore::transcriptionRow).optional();
    }

    public Optional<TranscriptionRow> inFlight(UUID assetId) {
        return jdbc.sql("SELECT * FROM transcription WHERE content_asset_id = :a AND status = 'SUBMITTED'")
            .param("a", assetId).query(AiStore::transcriptionRow).optional();
    }

    public Optional<TranscriptionRow> latestTranscription(UUID assetId) {
        return jdbc.sql("SELECT * FROM transcription WHERE content_asset_id = :a ORDER BY created_at DESC, id LIMIT 1")
            .param("a", assetId).query(AiStore::transcriptionRow).optional();
    }

    public boolean hasCompleted(UUID assetId, String sourceRef) {
        return jdbc.sql("SELECT count(*) FROM transcription WHERE content_asset_id = :a AND source_ref = :r AND status = 'COMPLETED'")
            .param("a", assetId).param("r", sourceRef).query(Long.class).single() > 0;
    }

    public void markSubmitted(UUID id, String jobRef) {
        jdbc.sql("UPDATE transcription SET provider_job_ref = :ref, attempts = attempts + 1 WHERE id = :id AND status = 'SUBMITTED'")
            .param("ref", jobRef).param("id", id).update();
    }

    public void markCompleted(UUID id, String language, int audioSeconds, long costMicroUsd) {
        jdbc.sql("""
                UPDATE transcription SET status = 'COMPLETED', language = :lang, audio_seconds = :secs, cost_micro_usd = :cost,
                       completed_at = now(), failure_code = NULL, failure_detail = NULL
                WHERE id = :id""")
            .param("lang", language).param("secs", audioSeconds).param("cost", costMicroUsd).param("id", id).update();
    }

    public void markFailed(UUID id, String code, String detail) {
        jdbc.sql("UPDATE transcription SET status = 'FAILED', failure_code = :code, failure_detail = :detail, completed_at = now() WHERE id = :id")
            .param("code", code).param("detail", detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000))
            .param("id", id).update();
    }

    // --- generations ---------------------------------------------------------------------------------

    /** Empty when a run of the task is already in flight for the asset. */
    public Optional<UUID> insertGeneration(UUID assetId, String task, int transcriptVersion, String promptVersion, String provider,
                                           String instructions, UUID requestedBy) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO ai_generation (id, content_asset_id, task, transcript_version, prompt_version, provider, instructions, requested_by)
                VALUES (:id, :a, :task, :tv, :pv, :provider, :instructions, :by)
                ON CONFLICT (content_asset_id, task) WHERE status = 'SUBMITTED' DO NOTHING""")
            .param("id", id).param("a", assetId).param("task", task).param("tv", transcriptVersion).param("pv", promptVersion)
            .param("provider", provider).param("instructions", instructions).param("by", requestedBy)
            .update();
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    public Optional<GenerationRow> generation(UUID id) {
        return jdbc.sql("SELECT * FROM ai_generation WHERE id = :id FOR UPDATE").param("id", id).query(AiStore::generationRow).optional();
    }

    public Optional<GenerationRow> generationInFlight(UUID assetId, String task) {
        return jdbc.sql("SELECT * FROM ai_generation WHERE content_asset_id = :a AND task = :t AND status = 'SUBMITTED'")
            .param("a", assetId).param("t", task).query(AiStore::generationRow).optional();
    }

    public boolean hasCompletedGeneration(UUID assetId, String task, int transcriptVersion, String promptVersion) {
        return jdbc.sql("""
                SELECT count(*) FROM ai_generation WHERE content_asset_id = :a AND task = :t AND transcript_version = :tv
                   AND prompt_version = :pv AND status = 'COMPLETED'""")
            .param("a", assetId).param("t", task).param("tv", transcriptVersion).param("pv", promptVersion)
            .query(Long.class).single() > 0;
    }

    /** Latest run of each task. */
    public List<GenerationRow> latestGenerations(UUID assetId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (task) * FROM ai_generation WHERE content_asset_id = :a ORDER BY task, created_at DESC, id""")
            .param("a", assetId).query(AiStore::generationRow).list();
    }

    /** Adds one model call's usage. */
    public void addUsage(UUID id, String model, int inputTokens, int outputTokens, int cacheReadTokens, long costMicroUsd) {
        jdbc.sql("""
                UPDATE ai_generation SET model = :model, input_tokens = input_tokens + :in, output_tokens = output_tokens + :out,
                       cache_read_tokens = cache_read_tokens + :cache, cost_micro_usd = cost_micro_usd + :cost WHERE id = :id""")
            .param("model", model).param("in", inputTokens).param("out", outputTokens).param("cache", cacheReadTokens)
            .param("cost", costMicroUsd).param("id", id).update();
    }

    public void generationAttempted(UUID id) {
        jdbc.sql("UPDATE ai_generation SET attempts = attempts + 1 WHERE id = :id").param("id", id).update();
    }

    public void generationCompleted(UUID id) {
        jdbc.sql("UPDATE ai_generation SET status = 'COMPLETED', completed_at = now() WHERE id = :id").param("id", id).update();
    }

    public void generationFailed(UUID id, String code, String detail) {
        jdbc.sql("UPDATE ai_generation SET status = 'FAILED', failure_code = :c, failure_detail = :d, completed_at = now() WHERE id = :id")
            .param("c", code).param("d", detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000))
            .param("id", id).update();
    }

    // --- segments ------------------------------------------------------------------------------------

    public int nextTranscriptVersion(UUID assetId) {
        return jdbc.sql("SELECT COALESCE(max(transcript_version), 0) + 1 FROM transcript_segment WHERE content_asset_id = :a")
            .param("a", assetId).query(Integer.class).single();
    }

    public void insertSegments(UUID assetId, int version, List<SpeechToText.Segment> segments) {
        int seq = 0;
        for (SpeechToText.Segment s : segments) {
            jdbc.sql("""
                    INSERT INTO transcript_segment (content_asset_id, transcript_version, seq, start_ms, end_ms, speaker, text, confidence)
                    VALUES (:a, :v, :seq, :start, :end, :speaker, :text, :conf)""")
                .param("a", assetId).param("v", version).param("seq", ++seq).param("start", s.startMs()).param("end", s.endMs())
                .param("speaker", s.speaker()).param("text", s.text().strip()).param("conf", s.confidence())
                .update();
        }
    }

    public List<SegmentRow> segments(UUID assetId, int version) {
        return jdbc.sql("""
                SELECT seq, start_ms, end_ms, speaker, text, confidence FROM transcript_segment
                WHERE content_asset_id = :a AND transcript_version = :v ORDER BY seq""")
            .param("a", assetId).param("v", version)
            .query((rs, n) -> new SegmentRow(rs.getInt("seq"), rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("speaker"),
                rs.getString("text"), rs.getObject("confidence") == null ? null : rs.getDouble("confidence")))
            .list();
    }

    /**
     * Full-text search over the approved transcripts of {@code allowedAssetIds} only. Empty list → no results.
     */
    public List<SearchHit> search(Collection<UUID> allowedAssetIds, String query, int limit) {
        if (allowedAssetIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT s.content_asset_id, s.start_ms, s.end_ms,
                       ts_headline('english', s.text, q, 'StartSel=<b>, StopSel=</b>, MaxFragments=1') AS snippet,
                       ts_rank_cd(s.tsv, q) AS rank
                FROM transcript_segment s
                JOIN lecture_artifact a ON a.content_asset_id = s.content_asset_id AND a.artifact_type = 'TRANSCRIPT'
                     AND a.status = 'APPROVED' AND a.transcript_version = s.transcript_version,
                     websearch_to_tsquery('english', :q) q
                WHERE s.content_asset_id IN (:allowed) AND s.tsv @@ q
                ORDER BY rank DESC, s.content_asset_id, s.start_ms
                LIMIT :limit""")
            .param("q", query).param("allowed", allowedAssetIds).param("limit", limit)
            .query((rs, n) -> new SearchHit(rs.getObject("content_asset_id", UUID.class), rs.getLong("start_ms"),
                rs.getLong("end_ms"), rs.getString("snippet"), rs.getDouble("rank")))
            .list();
    }

    /**
     * Q&A retrieval: segments of approved transcripts of {@code allowedAssetIds} matching ANY meaningful word of the
     * question (ranked by how many and how close), best first, at or above {@code minRank}. Questions are prose, so
     * requiring every word in one segment (as search does) would miss most answers.
     */
    public List<Passage> retrieve(Collection<UUID> allowedAssetIds, String question, int limit, double minRank) {
        if (allowedAssetIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                WITH q AS (
                    SELECT to_tsquery('english', string_agg(DISTINCT quote_literal(lex), ' | ')) AS q
                    FROM unnest(tsvector_to_array(to_tsvector('english', :question))) AS lex)
                SELECT s.content_asset_id, s.transcript_version, s.seq, s.start_ms, s.end_ms, s.text, ts_rank_cd(s.tsv, q.q) AS rank
                FROM transcript_segment s
                JOIN lecture_artifact a ON a.content_asset_id = s.content_asset_id AND a.artifact_type = 'TRANSCRIPT'
                     AND a.status = 'APPROVED' AND a.transcript_version = s.transcript_version
                CROSS JOIN q
                WHERE q.q IS NOT NULL AND s.content_asset_id IN (:allowed) AND s.tsv @@ q.q AND ts_rank_cd(s.tsv, q.q) >= :minRank
                ORDER BY rank DESC, s.content_asset_id, s.seq
                LIMIT :limit""")
            .param("question", question).param("allowed", allowedAssetIds).param("limit", limit).param("minRank", minRank)
            .query((rs, n) -> new Passage(rs.getObject("content_asset_id", UUID.class), rs.getInt("transcript_version"),
                rs.getInt("seq"), rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("text"), rs.getDouble("rank")))
            .list();
    }

    public List<SegmentRow> segmentRange(UUID assetId, int version, int fromSeq, int toSeq) {
        return jdbc.sql("""
                SELECT seq, start_ms, end_ms, speaker, text, confidence FROM transcript_segment
                WHERE content_asset_id = :a AND transcript_version = :v AND seq BETWEEN :from AND :to ORDER BY seq""")
            .param("a", assetId).param("v", version).param("from", fromSeq).param("to", toSeq)
            .query((rs, n) -> new SegmentRow(rs.getInt("seq"), rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("speaker"),
                rs.getString("text"), rs.getObject("confidence") == null ? null : rs.getDouble("confidence")))
            .list();
    }

    // --- Q&A log -------------------------------------------------------------------------------------

    public record QaLog(UUID userId, UUID enrollmentId, UUID organizationId, UUID courseId, String questionSha256,
                        Collection<UUID> retrievedAssetIds, String outcome, String model, int inputTokens, int outputTokens,
                        long costMicroUsd, boolean grounded, int citationCount, int droppedCitationCount) {
    }

    public UUID logQuestion(QaLog log) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ai_qa_log (id, user_id, enrollment_id, organization_id, course_id, question_sha256, retrieved_asset_ids,
                       outcome, model, input_tokens, output_tokens, cost_micro_usd, grounded, citation_count, dropped_citation_count)
                VALUES (:id, :u, :e, :o, :c, :sha, CAST(:assets AS uuid[]), :outcome, :model, :in, :out, :cost, :grounded, :cites, :dropped)""")
            .param("id", id).param("u", log.userId()).param("e", log.enrollmentId()).param("o", log.organizationId())
            .param("c", log.courseId()).param("sha", log.questionSha256())
            .param("assets", "{" + String.join(",", log.retrievedAssetIds().stream().map(UUID::toString).toList()) + "}")
            .param("outcome", log.outcome()).param("model", log.model()).param("in", log.inputTokens()).param("out", log.outputTokens())
            .param("cost", log.costMicroUsd()).param("grounded", log.grounded()).param("cites", log.citationCount())
            .param("dropped", log.droppedCitationCount())
            .update();
        return id;
    }

    public long questionsSince(UUID userId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM ai_qa_log WHERE user_id = :u AND created_at >= :since")
            .param("u", userId).param("since", Timestamp.from(since)).query(Long.class).single();
    }

    /** False when the question isn't the user's. */
    public boolean feedback(UUID questionId, UUID userId, int value) {
        return jdbc.sql("UPDATE ai_qa_log SET feedback = :v WHERE id = :id AND user_id = :u")
            .param("v", value).param("id", questionId).param("u", userId).update() == 1;
    }

    public int purgeQuestionsBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM ai_qa_log WHERE created_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update();
    }

    // --- artifacts -----------------------------------------------------------------------------------

    public UUID insertArtifact(UUID assetId, String type, String sourceRef, Integer transcriptVersion, String status,
                               String contentJson, String objectKey, String provider, String model, Integer audioSeconds,
                               Long costMicroUsd, UUID transcriptionId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO lecture_artifact (id, content_asset_id, artifact_type, source_ref, transcript_version, artifact_version,
                       status, content, object_key, provider, model, audio_seconds, cost_micro_usd, transcription_id)
                VALUES (:id, :a, :type, :ref, :tv,
                        (SELECT COALESCE(max(artifact_version), 0) + 1 FROM lecture_artifact WHERE content_asset_id = :a AND artifact_type = :type),
                        :status, CAST(:content AS jsonb), :key, :provider, :model, :secs, :cost, :tid)""")
            .param("id", id).param("a", assetId).param("type", type).param("ref", sourceRef).param("tv", transcriptVersion)
            .param("status", status).param("content", contentJson).param("key", objectKey).param("provider", provider)
            .param("model", model).param("secs", audioSeconds).param("cost", costMicroUsd).param("tid", transcriptionId)
            .update();
        return id;
    }

    /** Retires the currently approved artifact of a type, so another can be approved. */
    public void supersedeApproved(UUID assetId, String type) {
        jdbc.sql("UPDATE lecture_artifact SET status = 'SUPERSEDED' WHERE content_asset_id = :a AND artifact_type = :t AND status = 'APPROVED'")
            .param("a", assetId).param("t", type).update();
    }

    public Optional<ArtifactRow> approved(UUID assetId, String type) {
        return jdbc.sql("SELECT * FROM lecture_artifact WHERE content_asset_id = :a AND artifact_type = :t AND status = 'APPROVED'")
            .param("a", assetId).param("t", type).query(AiStore::artifactRow).optional();
    }

    public List<ArtifactRow> approved(Collection<UUID> assetIds, String type) {
        if (assetIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM lecture_artifact WHERE content_asset_id IN (:a) AND artifact_type = :t AND status = 'APPROVED'")
            .param("a", assetIds).param("t", type).query(AiStore::artifactRow).list();
    }

    public List<ArtifactRow> artifacts(UUID assetId) {
        return jdbc.sql("SELECT * FROM lecture_artifact WHERE content_asset_id = :a ORDER BY artifact_type, artifact_version DESC")
            .param("a", assetId).query(AiStore::artifactRow).list();
    }

    public Optional<ArtifactRow> artifact(UUID assetId, UUID artifactId) {
        return jdbc.sql("SELECT * FROM lecture_artifact WHERE id = :id AND content_asset_id = :a FOR UPDATE")
            .param("id", artifactId).param("a", assetId).query(AiStore::artifactRow).optional();
    }

    public void review(UUID artifactId, String status, UUID reviewer, String note) {
        jdbc.sql("UPDATE lecture_artifact SET status = :s, reviewed_by = :by, reviewed_at = now(), review_note = :note WHERE id = :id")
            .param("s", status).param("by", reviewer).param("note", note).param("id", artifactId).update();
    }

    public void attachGeneration(UUID artifactId, UUID generationId, String promptVersion, int inputTokens, int outputTokens) {
        jdbc.sql("""
                UPDATE lecture_artifact SET generation_id = :g, prompt_template_version = :pv, input_tokens = :in, output_tokens = :out
                WHERE id = :id""")
            .param("g", generationId).param("pv", promptVersion).param("in", inputTokens).param("out", outputTokens)
            .param("id", artifactId).update();
    }

    public void edit(UUID artifactId, String contentJson, UUID editor) {
        jdbc.sql("UPDATE lecture_artifact SET content = CAST(:c AS jsonb), edited_by = :by, edited_at = now() WHERE id = :id")
            .param("c", contentJson).param("by", editor).param("id", artifactId).update();
    }

    public void moveObject(UUID artifactId, String objectKey) {
        jdbc.sql("UPDATE lecture_artifact SET object_key = :k WHERE id = :id").param("k", objectKey).param("id", artifactId).update();
    }

    private static TranscriptionRow transcriptionRow(ResultSet rs, int n) throws SQLException {
        return new TranscriptionRow(rs.getObject("id", UUID.class), rs.getObject("content_asset_id", UUID.class),
            rs.getString("source_ref"), rs.getString("status"), rs.getString("provider"), rs.getString("model"),
            rs.getString("provider_job_ref"), rs.getInt("attempts"), rs.getString("language"),
            (Integer) rs.getObject("audio_seconds"), (Long) rs.getObject("cost_micro_usd"), rs.getString("failure_code"),
            rs.getString("failure_detail"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("completed_at")));
    }

    private static GenerationRow generationRow(ResultSet rs, int n) throws SQLException {
        return new GenerationRow(rs.getObject("id", UUID.class), rs.getObject("content_asset_id", UUID.class), rs.getString("task"),
            rs.getInt("transcript_version"), rs.getString("prompt_version"), rs.getString("status"), rs.getString("provider"),
            rs.getString("model"), rs.getInt("attempts"), rs.getInt("input_tokens"), rs.getInt("output_tokens"),
            rs.getInt("cache_read_tokens"), rs.getLong("cost_micro_usd"), rs.getString("instructions"), rs.getString("failure_code"),
            rs.getString("failure_detail"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("completed_at")));
    }

    private static ArtifactRow artifactRow(ResultSet rs, int n) throws SQLException {
        return new ArtifactRow(rs.getObject("id", UUID.class), rs.getObject("content_asset_id", UUID.class),
            rs.getString("artifact_type"), rs.getString("source_ref"), (Integer) rs.getObject("transcript_version"),
            rs.getInt("artifact_version"), rs.getString("status"), rs.getString("content"), rs.getString("object_key"),
            rs.getString("provider"), rs.getString("model"), (Integer) rs.getObject("audio_seconds"),
            (Long) rs.getObject("cost_micro_usd"), rs.getObject("reviewed_by", UUID.class), instant(rs.getTimestamp("reviewed_at")),
            rs.getString("review_note"), instant(rs.getTimestamp("created_at")), rs.getObject("edited_by", UUID.class),
            instant(rs.getTimestamp("edited_at")));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
