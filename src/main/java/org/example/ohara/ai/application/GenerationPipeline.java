package org.example.ohara.ai.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.example.ohara.ai.application.StudyMaterial.Task;
import org.example.ohara.ai.infrastructure.persistence.AiStore;
import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LLM study material from an approved transcript (docs 07 §3): summary + chapters (one call, automatically when a
 * transcript is approved), quiz and flashcard drafts (on request). Every answer is parsed and validated; an invalid one
 * gets one repair attempt, a refusal is recorded as such. Results are GENERATED drafts that staff review: nothing
 * reaches learners unapproved. Usage and cost accumulate on the run, including repair calls.
 * <p>
 * Idempotency: one run of a task in flight per asset, and the automatic summary is made once per
 * (transcript version, prompt version), so replayed approvals don't spend again.
 */
@Component
class GenerationPipeline {

    static final String JOB = "AI_GENERATE";

    record GenerateWork(UUID generationId) {
    }

    private static final Logger log = LoggerFactory.getLogger(GenerationPipeline.class);

    private final AiStore store;
    private final LlmClient llm;
    private final JobScheduler jobs;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final JsonMapper strict;
    private final boolean autoSummarize;
    private final long inputMicroUsdPerMTok;
    private final long outputMicroUsdPerMTok;
    private final MeterRegistry meters;
    private final int maxAttempts;

    GenerationPipeline(AiStore store, LlmClient llm, JobScheduler jobs, TransactionTemplate tx, JsonMapper json,
                       @Value("${ohara.ai.auto-summarize:true}") boolean autoSummarize,
                       @Value("${ohara.ai.pricing.llm-input-micro-usd-per-mtok:0}") long inputMicroUsdPerMTok,
                       @Value("${ohara.ai.pricing.llm-output-micro-usd-per-mtok:0}") long outputMicroUsdPerMTok,
                       @Value("${ohara.ai.llm.max-attempts:3}") int maxAttempts, MeterRegistry meters) {
        this.meters = meters;
        this.store = store;
        this.llm = llm;
        this.jobs = jobs;
        this.tx = tx;
        this.json = json;
        this.strict = StudyMaterial.strict(json);
        this.autoSummarize = autoSummarize;
        this.inputMicroUsdPerMTok = inputMicroUsdPerMTok;
        this.outputMicroUsdPerMTok = outputMicroUsdPerMTok;
        this.maxAttempts = maxAttempts;
    }

    /** Queues a run (joins the caller's transaction); empty if one is already in flight. */
    Optional<UUID> request(UUID assetId, Task task, int transcriptVersion, String instructions, UUID requestedBy) {
        Optional<UUID> id = store.insertGeneration(assetId, task.name(), transcriptVersion, task.promptVersion, llm.provider(),
            instructions, requestedBy);
        id.ifPresent(g -> jobs.enqueue(JobRequest.of(JOB, JOB + ":" + g, new GenerateWork(g)).withMaxAttempts(maxAttempts + 2)));
        return id;
    }

    /** A transcript version became the approved one: summarize it, once. */
    void transcriptApproved(UUID assetId, int transcriptVersion) {
        Task task = Task.SUMMARY_CHAPTERS;
        if (autoSummarize && !store.hasCompletedGeneration(assetId, task.name(), transcriptVersion, task.promptVersion)) {
            request(assetId, task, transcriptVersion, null, null);
        }
    }

    /** AI_GENERATE. Provider outages are rethrown (the job retries with backoff) until the run's attempts run out. */
    void generate(GenerateWork work) {
        AiStore.GenerationRow run = tx.execute(s -> store.generation(work.generationId()).orElse(null));
        if (run == null || !run.status().equals("SUBMITTED")) {
            return;
        }
        AiStore.ArtifactRow transcript = store.approved(run.assetId(), "TRANSCRIPT").orElse(null);
        if (transcript == null || transcript.transcriptVersion() != run.transcriptVersion()) {
            fail(run, "transcript_changed", "The approved transcript changed; request again");
            return;
        }
        List<AiStore.SegmentRow> segments = store.segments(run.assetId(), run.transcriptVersion());
        if (segments.isEmpty()) {
            fail(run, "no_transcript", "The transcript is empty");
            return;
        }
        List<StudyMaterial.Span> spans = segments.stream().map(s -> new StudyMaterial.Span(s.startMs(), s.endMs())).toList();
        Task task = Task.valueOf(run.task());
        String lines = segments.stream().map(s -> "[" + s.startMs() + "-" + s.endMs() + "] " + s.text()).collect(Collectors.joining("\n"));
        String prompt = StudyMaterial.user(task, lines, run.instructions());
        tx.executeWithoutResult(s -> store.generationAttempted(run.id()));

        Object result;
        try {
            LlmClient.Response first = call(run, task, prompt);
            if (first.stopReason() == LlmClient.StopReason.REFUSAL) {
                fail(run, "llm_refused", "The model declined to answer");
                return;
            }
            try {
                result = StudyMaterial.parse(strict, task, first.text(), spans);
            } catch (StudyMaterial.Invalid invalid) {
                log.warn("Generation {} answer invalid ({}); asking for a repair", run.id(), invalid.getMessage());
                LlmClient.Response second = call(run, task, StudyMaterial.repair(prompt, first.text(), invalid.problems));
                if (second.stopReason() == LlmClient.StopReason.REFUSAL) {
                    fail(run, "llm_refused", "The model declined to answer");
                    return;
                }
                result = StudyMaterial.parse(strict, task, second.text(), spans);
            }
        } catch (StudyMaterial.Invalid stillInvalid) {
            fail(run, "output_invalid", stillInvalid.getMessage());
            return;
        } catch (LlmClient.Unavailable outage) {
            if (run.attempts() + 1 >= maxAttempts) {
                fail(run, "llm_unavailable", outage.getMessage());
                return;
            }
            throw outage;
        }
        store(run, transcript, task, result);
    }

    private LlmClient.Response call(AiStore.GenerationRow run, Task task, String prompt) {
        LlmClient.Response response = llm.complete(new LlmClient.Request(task.name(), task.tier, StudyMaterial.system(), prompt,
            StudyMaterial.schema(task), task.maxOutputTokens));
        long cost = (long) Math.ceil((response.inputTokens() * (double) inputMicroUsdPerMTok
            + response.outputTokens() * (double) outputMicroUsdPerMTok) / 1_000_000);
        meters.counter("ohara.ai.cost.micro_usd", "kind", "llm").increment(cost);
        tx.executeWithoutResult(s -> store.addUsage(run.id(), response.model(), response.inputTokens(), response.outputTokens(),
            response.cacheReadTokens(), cost));
        return response;
    }

    private void store(AiStore.GenerationRow run, AiStore.ArtifactRow transcript, Task task, Object result) {
        tx.executeWithoutResult(s -> {
            AiStore.GenerationRow current = store.generation(run.id()).orElseThrow();
            if (!current.status().equals("SUBMITTED")) {
                return;
            }
            switch (result) {
                case StudyMaterial.SummaryChapters sc -> {
                    insert(current, transcript, "SUMMARY", new StudyMaterial.Summary(sc.shortSummary().strip(), sc.detailedSummary().strip()));
                    insert(current, transcript, "CHAPTERS", new StudyMaterial.Chapters(sc.chapters()));
                }
                case StudyMaterial.Quiz quiz -> insert(current, transcript, "QUIZ_DRAFT", quiz);
                case StudyMaterial.Flashcards cards -> insert(current, transcript, "FLASHCARDS", cards);
                default -> throw new IllegalStateException(result.getClass().getName());
            }
            store.generationCompleted(run.id());
            meters.counter("ohara.ai.generations", "task", task.name(), "outcome", "completed").increment();
        });
        log.info("Generation {} ({}) for asset {} completed", run.id(), task, run.assetId());
    }

    private void insert(AiStore.GenerationRow run, AiStore.ArtifactRow transcript, String type, Object content) {
        UUID id = store.insertArtifact(run.assetId(), type, transcript.sourceRef(), run.transcriptVersion(), "GENERATED",
            json.writeValueAsString(content), null, run.provider(), run.model(), null, null, null);
        store.attachGeneration(id, run.id(), run.promptVersion(), run.inputTokens(), run.outputTokens());
    }

    private void fail(AiStore.GenerationRow run, String code, String detail) {
        log.warn("Generation {} failed ({}): {}", run.id(), code, detail);
        tx.executeWithoutResult(s -> store.generationFailed(run.id(), code, detail));
        meters.counter("ohara.ai.generations", "task", run.task(), "outcome", code).increment();
    }
}
