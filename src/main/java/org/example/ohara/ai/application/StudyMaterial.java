package org.example.ohara.ai.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What each generation task must produce, how it's checked, and how it's stored. A model's answer is accepted only if
 * it parses into these records and passes {@link #validate}: lengths, counts, timestamps inside the lecture,
 * chapters in order, quiz answers pointing at a real option and citing a span of the transcript.
 */
final class StudyMaterial {

    enum Task {
        SUMMARY_CHAPTERS("summary-chapters/v1", LlmClient.Tier.STANDARD, 4000),
        QUIZ_DRAFT("quiz/v1", LlmClient.Tier.STANDARD, 4000),
        FLASHCARDS("flashcards/v1", LlmClient.Tier.FAST, 3000);

        final String promptVersion;
        final LlmClient.Tier tier;
        final int maxOutputTokens;

        Task(String promptVersion, LlmClient.Tier tier, int maxOutputTokens) {
            this.promptVersion = promptVersion;
            this.tier = tier;
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    record Chapter(Long startMs, String title) {
    }

    record SummaryChapters(String shortSummary, String detailedSummary, List<Chapter> chapters) {
    }

    record Question(String prompt, List<String> options, Integer answerIndex, String explanation, Long sourceStartMs, Long sourceEndMs) {
    }

    record Quiz(List<Question> questions) {
    }

    record Card(String front, String back, Long sourceStartMs) {
    }

    record Flashcards(List<Card> cards) {
    }

    /** Stored artifact bodies. */
    record Summary(String shortSummary, String detailedSummary) {
    }

    record Chapters(List<Chapter> chapters) {
    }

    record Span(long startMs, long endMs) {
    }

    static class Invalid extends Exception {
        final List<String> problems;

        Invalid(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = problems;
        }
    }

    private StudyMaterial() {
    }

    static String system() {
        return """
            You prepare study material for a recorded university lecture, using only the transcript you are given.
            Never add facts that are not in the transcript. Timestamps are milliseconds from the start of the lecture
            and must come from the transcript lines. Answer with one JSON object that matches the schema, and nothing else.""";
    }

    static String user(Task task, String transcriptLines, String instructions) {
        String ask = switch (task) {
            case SUMMARY_CHAPTERS -> """
                Write a short summary (at most 3 sentences), a detailed summary (at most 12 sentences) and chapters for
                navigation: each chapter starts where a new topic begins, the first at 0, in time order, with a title of
                at most 8 words.""";
            case QUIZ_DRAFT -> """
                Write up to 10 multiple-choice questions that test understanding of the lecture. Each has 4 options with
                exactly one correct answer (answerIndex, from 0), a one-sentence explanation, and the transcript span
                (sourceStartMs, sourceEndMs) that supports the answer.""";
            case FLASHCARDS -> """
                Write up to 20 flashcards for revision: a question or term on the front, a concise answer on the back,
                and the transcript position (sourceStartMs) it comes from.""";
        };
        String extra = instructions == null || instructions.isBlank() ? "" : "\nInstructor notes: " + instructions.strip() + "\n";
        return ask + extra + "\nTranscript (one line per segment, [startMs-endMs] text):\n" + transcriptLines;
    }

    static String repair(String original, String badAnswer, List<String> problems) {
        return original + "\n\nYour previous answer was invalid:\n- " + String.join("\n- ", problems)
            + "\nPrevious answer:\n" + (badAnswer.length() > 4000 ? badAnswer.substring(0, 4000) : badAnswer)
            + "\nAnswer again with a corrected JSON object only.";
    }

    static String schema(Task task) {
        return switch (task) {
            case SUMMARY_CHAPTERS -> """
                {"type":"object","required":["shortSummary","detailedSummary","chapters"],"properties":{
                 "shortSummary":{"type":"string"},"detailedSummary":{"type":"string"},
                 "chapters":{"type":"array","items":{"type":"object","required":["startMs","title"],
                   "properties":{"startMs":{"type":"integer"},"title":{"type":"string"}}}}}}""";
            case QUIZ_DRAFT -> """
                {"type":"object","required":["questions"],"properties":{"questions":{"type":"array","items":{"type":"object",
                 "required":["prompt","options","answerIndex","explanation","sourceStartMs","sourceEndMs"],"properties":{
                 "prompt":{"type":"string"},"options":{"type":"array","items":{"type":"string"}},"answerIndex":{"type":"integer"},
                 "explanation":{"type":"string"},"sourceStartMs":{"type":"integer"},"sourceEndMs":{"type":"integer"}}}}}}""";
            case FLASHCARDS -> """
                {"type":"object","required":["cards"],"properties":{"cards":{"type":"array","items":{"type":"object",
                 "required":["front","back","sourceStartMs"],"properties":{"front":{"type":"string"},"back":{"type":"string"},
                 "sourceStartMs":{"type":"integer"}}}}}}""";
        };
    }

    /** A mapper that rejects unknown fields, so a misnamed field is caught rather than silently dropped. */
    static JsonMapper strict(JsonMapper json) {
        return json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    /** Parses and validates an answer for {@code task} with a {@link #strict} mapper; returns the typed result. */
    static Object parse(JsonMapper strict, Task task, String text, List<Span> transcript) throws Invalid {
        Class<?> type = switch (task) {
            case SUMMARY_CHAPTERS -> SummaryChapters.class;
            case QUIZ_DRAFT -> Quiz.class;
            case FLASHCARDS -> Flashcards.class;
        };
        Object value;
        try {
            value = strict.readValue(text, type);
        } catch (JacksonException e) {
            throw new Invalid(List.of("Not a JSON object of the required shape: " + e.getOriginalMessage()));
        }
        validate(value, transcript);
        return value;
    }

    /** Also used for staff edits of a stored artifact body. */
    static void validate(Object value, List<Span> transcript) throws Invalid {
        long durationMs = transcript.stream().mapToLong(Span::endMs).max().orElse(0);
        List<String> problems = new ArrayList<>();
        switch (value) {
            case SummaryChapters s -> {
                text(problems, "shortSummary", s.shortSummary(), 600);
                text(problems, "detailedSummary", s.detailedSummary(), 4000);
                chapters(problems, s.chapters(), durationMs);
            }
            case Summary s -> {
                text(problems, "shortSummary", s.shortSummary(), 600);
                text(problems, "detailedSummary", s.detailedSummary(), 4000);
            }
            case Chapters c -> chapters(problems, c.chapters(), durationMs);
            case Quiz q -> {
                count(problems, "questions", q.questions(), 1, 20);
                for (int i = 0; q.questions() != null && i < q.questions().size(); i++) {
                    Question question = q.questions().get(i);
                    String at = "questions[" + i + "]";
                    if (question == null) {
                        problems.add(at + " is missing");
                        continue;
                    }
                    text(problems, at + ".prompt", question.prompt(), 500);
                    text(problems, at + ".explanation", question.explanation(), 1000);
                    count(problems, at + ".options", question.options(), 2, 6);
                    if (question.options() != null) {
                        Set<String> distinct = new HashSet<>();
                        for (String option : question.options()) {
                            text(problems, at + ".options[]", option, 300);
                            if (option != null && !distinct.add(option.strip().toLowerCase())) {
                                problems.add(at + ".options repeat \"" + option + "\"");
                            }
                        }
                        if (question.answerIndex() == null || question.answerIndex() < 0 || question.answerIndex() >= question.options().size()) {
                            problems.add(at + ".answerIndex must point at one of the options");
                        }
                    }
                    if (question.sourceStartMs() == null || question.sourceEndMs() == null || question.sourceStartMs() < 0
                        || question.sourceEndMs() <= question.sourceStartMs() || question.sourceEndMs() > durationMs) {
                        problems.add(at + " must cite a span inside the lecture (0-" + durationMs + " ms)");
                    } else if (transcript.stream().noneMatch(s -> s.startMs() < question.sourceEndMs() && s.endMs() > question.sourceStartMs())) {
                        problems.add(at + " cites a span with no transcript");
                    }
                }
            }
            case Flashcards f -> {
                count(problems, "cards", f.cards(), 1, 40);
                for (int i = 0; f.cards() != null && i < f.cards().size(); i++) {
                    Card card = f.cards().get(i);
                    if (card == null) {
                        problems.add("cards[" + i + "] is missing");
                        continue;
                    }
                    text(problems, "cards[" + i + "].front", card.front(), 300);
                    text(problems, "cards[" + i + "].back", card.back(), 600);
                    if (card.sourceStartMs() == null || card.sourceStartMs() < 0 || card.sourceStartMs() > durationMs) {
                        problems.add("cards[" + i + "].sourceStartMs must be inside the lecture");
                    }
                }
            }
            default -> throw new IllegalArgumentException(value.getClass().getName());
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
    }

    private static void chapters(List<String> problems, List<Chapter> chapters, long durationMs) {
        count(problems, "chapters", chapters, 1, 30);
        long previous = -1;
        for (int i = 0; chapters != null && i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            if (c == null || c.startMs() == null) {
                problems.add("chapters[" + i + "].startMs is missing");
                continue;
            }
            text(problems, "chapters[" + i + "].title", c.title(), 120);
            if (c.startMs() < 0 || c.startMs() >= Math.max(durationMs, 1)) {
                problems.add("chapters[" + i + "] starts outside the lecture");
            }
            if (c.startMs() <= previous) {
                problems.add("chapters must be in time order without repeats");
            }
            previous = c.startMs();
        }
    }

    private static void text(List<String> problems, String field, String value, int max) {
        if (value == null || value.isBlank()) {
            problems.add(field + " is empty");
        } else if (value.length() > max) {
            problems.add(field + " is longer than " + max + " characters");
        }
    }

    private static void count(List<String> problems, String field, List<?> list, int min, int max) {
        if (list == null || list.size() < min || list.size() > max) {
            problems.add(field + " must have " + min + " to " + max + " items");
        }
    }
}
